package com.jonasdurau.spectator.core.service;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.repository.CandleRepository;
import com.jonasdurau.spectator.integration.binance.BinanceRestClient;
import com.jonasdurau.spectator.integration.binance.BinanceWebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);
    
    // Como o Spectator é focado em Bitcoin, vamos deixar isso fixo por enquanto.
    private static final String TARGET_SYMBOL = "BTCUSDT";
    private static final String TIMEFRAME = "1h"; 

    private final CandleRepository candleRepository;
    private final BinanceRestClient restClient;
    private final BinanceWebSocketClient webSocketClient;

    public MarketDataService(CandleRepository candleRepository, 
                                  BinanceRestClient restClient, 
                                  BinanceWebSocketClient webSocketClient) {
        this.candleRepository = candleRepository;
        this.restClient = restClient;
        this.webSocketClient = webSocketClient;
    }

    /**
     * Este método é acionado automaticamente pelo Spring assim que a 
     * aplicação termina de subir e a porta 8080 está pronta.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startSync() {
        log.info("Spectator Engine Starting... Initializing Market Data Sync.");

        // 1. Carga Inicial (Seed) via REST
        seedHistoricalData();

        // 2. Conecta no WebSocket para atualizações em tempo real
        startRealtimeStream();
    }

    private void seedHistoricalData() {
        // Verifica se o banco já tem dados para não baixar os 1000 candles atoa toda vez
        Candle lastCandle = candleRepository.findTopBySymbolOrderByTimeDesc(TARGET_SYMBOL);
        
        if (lastCandle == null) {
            log.info("Database is empty for {}. Fetching last 1000 candles via REST...", TARGET_SYMBOL);
            List<Candle> history = restClient.fetchHistoricalCandles(TARGET_SYMBOL, TIMEFRAME, 1000);
            candleRepository.saveAll(history);
            log.info("Successfully saved {} historical candles to TimescaleDB.", history.size());
        } else {
            log.info("Database already contains data for {}. Last candle time: {}", TARGET_SYMBOL, lastCandle.getTime());
            // TODO Futuro: Lógica de preencher apenas o "gap" entre o lastCandle e o momento atual
        }
    }

    private void startRealtimeStream() {
        log.info("Opening WebSocket stream for {} timeframe...", TIMEFRAME);
        
        webSocketClient.connect(TARGET_SYMBOL, TIMEFRAME, incomingCandle -> {
            // Este bloco roda toda vez que a Binance manda um tick novo
            candleRepository.save(incomingCandle);
            log.debug("Saved tick for {}: Close Price = {}", incomingCandle.getSymbol(), incomingCandle.getClose());
        });
    }
}