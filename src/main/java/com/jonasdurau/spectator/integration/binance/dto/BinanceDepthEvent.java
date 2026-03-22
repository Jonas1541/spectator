package com.jonasdurau.spectator.integration.binance.dto;

import java.util.List;

public record BinanceDepthEvent(
        long lastUpdateId,
        List<List<String>> bids,
        List<List<String>> asks
) {}
