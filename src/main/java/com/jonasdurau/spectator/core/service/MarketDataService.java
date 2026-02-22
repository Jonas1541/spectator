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
        Candle lastCandle = candleRepository.findTopBySymbolOrderByTimeDesc(TARGET_SYMBOL);
        
        if (lastCandle == null) {
            log.info("Database is empty for {}. Fetching initial 1000 candles via REST...", TARGET_SYMBOL);
            List<Candle> history = restClient.fetchHistoricalCandles(TARGET_SYMBOL, TIMEFRAME, 1000);
            // Usando nosso upsert otimizado para salvar a carga inicial
            history.forEach(candleRepository::upsert);
            log.info("Successfully saved {} historical candles to TimescaleDB.", history.size());
        } else {
            log.info("Database contains data. Last candle time: {}", lastCandle.getTime());
            fillGap(lastCandle.getTime());
        }
    }

    private void fillGap(java.time.Instant lastCandleTime) {
        log.info("Checking for missing candles since {}...", lastCandleTime);
        java.time.Instant currentTime = lastCandleTime;
        java.time.Instant now = java.time.Instant.now();
        int totalFetched = 0;

        // Loop para paginar gaps que sejam maiores que 1000 candles
        while (currentTime.isBefore(now)) {
            List<Candle> batch = restClient.fetchHistoricalCandles(TARGET_SYMBOL, TIMEFRAME, 1000, currentTime);

            if (batch.isEmpty()) {
                break;
            }

            // Salva o lote usando o upsert nativo (seguro para overlaps)
            batch.forEach(candleRepository::upsert);
            totalFetched += batch.size();

            java.time.Instant lastFetchedTime = batch.get(batch.size() - 1).getTime();

            // Se a API retornou apenas o próprio candle de início (sem velas novas), saímos do loop
            if (lastFetchedTime.equals(currentTime) && batch.size() == 1) {
                break;
            }

            currentTime = lastFetchedTime;
            
            // Pausa de 100ms para evitar banimento (Rate Limit) da Binance caso o loop rode muitas vezes
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Se totalFetched > 1 é porque baixamos mais coisas além do candle de overlap
        if (totalFetched > 1) { 
            log.info("Gap filled. Fetched and synced {} new/updated candles.", totalFetched);
        } else {
            log.info("Database is already up to date.");
        }
    }

    private void startRealtimeStream() {
        log.info("Opening WebSocket stream for {} timeframe...", TIMEFRAME);
        
        webSocketClient.connect(TARGET_SYMBOL, TIMEFRAME, incomingCandle -> {
            // Este bloco roda toda vez que a Binance manda um tick novo
            candleRepository.upsert(incomingCandle);
            log.debug("Saved tick for {}: Close Price = {}", incomingCandle.getSymbol(), incomingCandle.getClose());
        });
    }
}