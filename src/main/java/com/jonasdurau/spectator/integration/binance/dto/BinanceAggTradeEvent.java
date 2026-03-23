package com.jonasdurau.spectator.integration.binance.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceAggTradeEvent(
        @JsonProperty("p") String price,
        @JsonProperty("q") String quantity,
        @JsonProperty("m") boolean isBuyerMaker
) {}
