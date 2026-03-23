package com.jonasdurau.spectator.core.service;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.repository.CandleRepository;
import com.jonasdurau.spectator.integration.binance.BinanceRestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
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

    /**
     * Verifica se o banco de dados contém dados suficientes para o período solicitado.
     * Se menos de 95% das velas esperadas existirem, força o download completo via syncPeriod.
     */
    public void ensureDataAvailable(String symbol, String timeframe, Instant start, Instant end) {
        long totalMinutes = Duration.between(start, end).toMinutes();

        long minutesPerCandle = switch (timeframe) {
            case "1m"  -> 1;
            case "3m"  -> 3;
            case "5m"  -> 5;
            case "15m" -> 15;
            case "30m" -> 30;
            case "1h"  -> 60;
            case "2h"  -> 120;
            case "4h"  -> 240;
            case "1d"  -> 1440;
            default -> throw new IllegalArgumentException("Timeframe desconhecido: " + timeframe);
        };

        long expectedCandles = totalMinutes / minutesPerCandle;
        long actualCandles = candleRepository.countBySymbolAndTimeframeAndTimeBetween(symbol, timeframe, start, end);
        double coverage = expectedCandles > 0 ? (double) actualCandles / expectedCandles : 0.0;

        log.info("[DataCheck] {} {} | Esperado: {} | Encontrado: {} | Cobertura: {}%",
                symbol, timeframe, expectedCandles, actualCandles, String.format("%.1f", coverage * 100));

        if (coverage < 0.95) {
            log.warn("[DataCheck] Cobertura insuficiente ({}%). Forçando sincronização completa de {} {} de {} até {}.",
                    String.format("%.1f", coverage * 100), symbol, timeframe, start, end);
            syncPeriod(symbol, timeframe, start, end);
        }
    }
}