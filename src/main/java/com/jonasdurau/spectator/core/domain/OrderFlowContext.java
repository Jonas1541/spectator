package com.jonasdurau.spectator.core.domain;

public record OrderFlowContext(
        double cumulativeVolumeDelta,
        double currentFundingRate
) {}
