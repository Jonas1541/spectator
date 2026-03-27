package com.jonasdurau.spectator.core.service;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.domain.MarketRegime;
import com.jonasdurau.spectator.core.repository.CandleRepository;
import com.jonasdurau.spectator.core.strategy.RegimeAnalyzerService;
import com.jonasdurau.spectator.integration.binance.BinanceRestClient;
import com.jonasdurau.spectator.integration.binance.BinanceWebSocketClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jonasdurau.spectator.ui.broadcaster.MarketDataBroadcaster;
import com.jonasdurau.spectator.ui.broadcaster.MarketTick;
import com.jonasdurau.spectator.integration.binance.BinanceDepthWebSocketClient;
import com.jonasdurau.spectator.integration.binance.BinanceAggTradeWebSocketClient;
import com.jonasdurau.spectator.integration.binance.BinanceMarkPriceWebSocketClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.time.Instant;

@Service
@ConditionalOnProperty(name = "spectator.mode.backtest-only", havingValue = "false", matchIfMissing = true)
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    @Value("${spectator.symbols}")
    private String symbolsConfig;

    private final CandleRepository candleRepository;
    private final BinanceRestClient restClient;
    private final ObjectMapper objectMapper;
    private final RegimeAnalyzerService regimeAnalyzerService;
    private final MarketDataBroadcaster broadcaster;
    private final PositionManagerService positionManagerService;
    private final StrategyEngineService strategyEngineService;
    private final OrderBookService orderBookService;
    private final OrderFlowService orderFlowService;

    public MarketDataService(CandleRepository candleRepository,
            BinanceRestClient restClient,
            ObjectMapper objectMapper,
            RegimeAnalyzerService regimeAnalyzerService,
            MarketDataBroadcaster broadcaster,
            PositionManagerService positionManagerService,
            StrategyEngineService strategyEngineService,
            OrderBookService orderBookService,
            OrderFlowService orderFlowService) {
        this.candleRepository = candleRepository;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.regimeAnalyzerService = regimeAnalyzerService;
        this.broadcaster = broadcaster;
        this.positionManagerService = positionManagerService;
        this.strategyEngineService = strategyEngineService;
        this.orderBookService = orderBookService;
        this.orderFlowService = orderFlowService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startSync() {
        String[] symbols = symbolsConfig.split(",");
        log.info("Spectator Engine Starting... Initializing Multi-Asset Sync for {} symbols: {}", symbols.length, symbolsConfig);

        for (String symbol : symbols) {
            String s = symbol.trim().toUpperCase();
            log.info("--- Bootstrapping symbol: {} ---", s);

            // 1. Carga Inicial (Seed) via REST
            seedHistoricalData(s, "1h");
            seedHistoricalData(s, "4h");

            // 2. Conecta no WebSocket para atualizações em tempo real
            startRealtimeStream(s, "1h");
            startRealtimeStream(s, "4h");

            // 3. Conecta no WebSocket de Order Book (Depth 5 Níveis)
            new BinanceDepthWebSocketClient(objectMapper, orderBookService).connect(s);

            // 4. Conecta nos WebSockets de Order Flow (AggTrades e MarkPrice)
            new BinanceAggTradeWebSocketClient(objectMapper, orderFlowService).connect(s);
            new BinanceMarkPriceWebSocketClient(objectMapper, orderFlowService).connect(s);
        }
    }

    private void seedHistoricalData(String symbol, String timeframe) {
        Candle lastCandle = candleRepository.findTopBySymbolAndTimeframeOrderByTimeDesc(symbol, timeframe);

        if (lastCandle == null) {
            log.info("Database is empty for {} ({}). Fetching initial 1000 candles via REST...", symbol, timeframe);
            List<Candle> history = restClient.fetchHistoricalCandles(symbol, timeframe, 1000);
            history.forEach(candleRepository::upsert);
            log.info("Successfully saved {} historical candles for {} to TimescaleDB.", history.size(), symbol);
        } else {
            log.info("Database contains data for {} ({}). Last candle time: {}", symbol, timeframe, lastCandle.getTime());
            fillGap(symbol, timeframe, lastCandle.getTime());
        }
    }

    private void fillGap(String symbol, String timeframe, Instant lastCandleTime) {
        log.info("Checking for missing candles for {} since {}...", symbol, lastCandleTime);
        Instant currentTime = lastCandleTime;
        Instant now = Instant.now();
        int totalFetched = 0;

        while (currentTime.isBefore(now)) {
            List<Candle> batch = restClient.fetchHistoricalCandles(symbol, timeframe, 1000, currentTime);

            if (batch.isEmpty()) {
                break;
            }

            batch.forEach(candleRepository::upsert);
            totalFetched += batch.size();

            Instant lastFetchedTime = batch.get(batch.size() - 1).getTime();

            if (lastFetchedTime.equals(currentTime) && batch.size() == 1) {
                break;
            }

            currentTime = lastFetchedTime;

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (totalFetched > 1) {
            log.info("Gap filled for {}. Fetched and synced {} new/updated candles.", symbol, totalFetched);
        } else {
            log.info("Database is already up to date for {}.", symbol);
        }
    }

    private void startRealtimeStream(String symbol, String timeframe) {
        log.info("Opening WebSocket stream for {} ({})...", symbol, timeframe);

        new BinanceWebSocketClient(objectMapper, incomingCandle -> {
            candleRepository.upsert(incomingCandle);

            if ("1h".equals(timeframe)) {
                List<Candle> recent1h = candleRepository.findLastCandles(symbol, "1h", 250);
                Collections.reverse(recent1h);

                List<Candle> recent4h = candleRepository.findLastCandles(symbol, "4h", 250);
                Collections.reverse(recent4h);

                MarketRegime currentRegime = MarketRegime.SIDEWAYS;
                if (recent4h.size() > 50) {
                    currentRegime = regimeAnalyzerService.analyze(recent4h);
                }

                log.info("Tick: {} | Price: {} | 4H Regime: {}", incomingCandle.getSymbol(), incomingCandle.getClose(),
                        currentRegime);

                positionManagerService.evaluateLiveTick(symbol, incomingCandle.getClose(), currentRegime);
                strategyEngineService.processTick(symbol, incomingCandle.getClose(), currentRegime, recent1h);

                List<com.jonasdurau.spectator.core.domain.Position> openPositions = positionManagerService
                        .getOpenPositions(symbol);

                broadcaster.broadcast(new MarketTick(incomingCandle, currentRegime, openPositions));
            } else {
                log.info("Saved {} 4H tick: {} | Price: {}", symbol, incomingCandle.getSymbol(), incomingCandle.getClose());
            }
        }).connect(symbol, timeframe);
    }
}