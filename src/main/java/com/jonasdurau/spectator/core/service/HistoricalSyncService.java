package com.jonasdurau.spectator.core.service;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.repository.CandleRepository;
import com.jonasdurau.spectator.integration.binance.BinanceRestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class HistoricalSyncService {

    private static final Logger log = LoggerFactory.getLogger(HistoricalSyncService.class);
    
    private final BinanceRestClient restClient;
    private final CandleRepository candleRepository;

    public HistoricalSyncService(BinanceRestClient restClient, CandleRepository candleRepository) {
        this.restClient = restClient;
        this.candleRepository = candleRepository;
    }

    /**
     * Baixa blocos de 1000 candles da Binance até preencher todo o período solicitado.
     */
    public void syncPeriod(String symbol, String timeframe, Instant start, Instant end) {
        log.info("Sincronizando dados históricos para {} {} de {} até {}", symbol, timeframe, start, end);
        
        Instant currentTime = start;
        int totalSaved = 0;

        while (currentTime.isBefore(end)) {
            List<Candle> batch = restClient.fetchHistoricalCandles(symbol, timeframe, 1000, currentTime);
            
            if (batch.isEmpty()) break;

            // Filtra para não salvar candles além da data final solicitada
            List<Candle> validBatch = batch.stream()
                    .filter(c -> !c.getTime().isAfter(end))
                    .toList();

            validBatch.forEach(candleRepository::upsert);
            totalSaved += validBatch.size();

            Instant lastFetchedTime = batch.get(batch.size() - 1).getTime();
            
            // Se a API não avançou no tempo, saímos para evitar loop infinito
            if (lastFetchedTime.equals(currentTime) || lastFetchedTime.isAfter(end)) {
                break;
            }

            // O próximo lote começa 1 milissegundo após o último candle baixado
            currentTime = lastFetchedTime.plusMillis(1);

            try {
                Thread.sleep(100); // Evita banimento por Rate Limit da Binance
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Sincronização concluída. {} novos candles salvos.", totalSaved);
    }
}