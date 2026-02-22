package com.jonasdurau.spectator.integration.binance.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Record nativo do Java para mapear o JSON do WebSocket da Binance.
 * Ignoramos campos desconhecidos para evitar quebras se a API mudar.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceKlineEvent(
        @JsonProperty("s") String symbol,
        @JsonProperty("k") KlineData kline
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KlineData(
            @JsonProperty("t") long startTime,
            @JsonProperty("o") String open,
            @JsonProperty("h") String high,
            @JsonProperty("l") String low,
            @JsonProperty("c") String close,
            @JsonProperty("v") String volume,
            @JsonProperty("x") boolean isClosed
    ) {}
}