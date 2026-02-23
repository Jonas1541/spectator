package com.jonasdurau.spectator.ui.broadcaster;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.domain.MarketRegime;

public record MarketTick(Candle candle, MarketRegime regime) {}