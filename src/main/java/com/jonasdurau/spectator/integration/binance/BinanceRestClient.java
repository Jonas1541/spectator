package com.jonasdurau.spectator.integration.binance;

import com.jonasdurau.spectator.core.domain.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class BinanceRestClient {

    private static final Logger log = LoggerFactory.getLogger(BinanceRestClient.class);
    private final RestClient restClient;

    public BinanceRestClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Busca o histórico de candles da Binance.
     * * @param symbol   Ex: "BTCUSDT"
     * @param interval Ex: "4h" ou "1h"
     * @param limit    Máximo de 1000 por requisição
     */
    public List<Candle> fetchHistoricalCandles(String symbol, String interval, int limit) {
        log.info("Fetching {} {} candles for {}", limit, interval, symbol);

        // A Binance retorna uma lista de listas genéricas
        List<List<Object>> response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v3/klines")
                        .queryParam("symbol", symbol)
                        .queryParam("interval", interval)
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (response == null || response.isEmpty()) {
            return List.of();
        }

        return response.stream()
                .map(kline -> mapToCandle(symbol, kline))
                .collect(Collectors.toList());
    }

    /**
     * Busca o histórico de candles a partir de uma data específica (Gap Fill).
     */
    public List<Candle> fetchHistoricalCandles(String symbol, String interval, int limit, java.time.Instant startTime) {
        log.info("Fetching gap: {} {} candles for {} starting from {}", limit, interval, symbol, startTime);

        List<List<Object>> response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v3/klines")
                        .queryParam("symbol", symbol)
                        .queryParam("interval", interval)
                        .queryParam("limit", limit)
                        .queryParam("startTime", startTime.toEpochMilli()) // <-- O pulo do gato
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (response == null || response.isEmpty()) {
            return List.of();
        }

        return response.stream()
                .map(kline -> mapToCandle(symbol, kline))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Converte o array da Binance para a nossa entidade de domínio limpa.
     * Documentação: https://binance-docs.github.io/apidocs/spot/en/#kline-candlestick-data
     */
    private Candle mapToCandle(String symbol, List<Object> kline) {
        // kline.get(0) = Open time (Long)
        long openTimeMs = ((Number) kline.get(0)).longValue();
        
        // Convertendo para UTC, que é o padrão de facto para trading
        Instant time = Instant.ofEpochMilli(openTimeMs);

        // kline.get(1) a (5) = O, H, L, C, V (Vêm como String na API da Binance)
        double open = Double.parseDouble(kline.get(1).toString());
        double high = Double.parseDouble(kline.get(2).toString());
        double low = Double.parseDouble(kline.get(3).toString());
        double close = Double.parseDouble(kline.get(4).toString());
        double volume = Double.parseDouble(kline.get(5).toString());

        return new Candle(symbol, time, open, high, low, close, volume);
    }
}