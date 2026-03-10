package com.jonasdurau.spectator.core.domain;

import java.time.Instant;

public record RegimeChangeEvent(Instant time, MarketRegime regime) {}