package com.jonasdurau.spectator.integration.binance.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceMarkPriceEvent(
        @JsonProperty("s") String symbol,
        @JsonProperty("p") String markPrice,
        @JsonProperty("r") String fundingRate
) {}
