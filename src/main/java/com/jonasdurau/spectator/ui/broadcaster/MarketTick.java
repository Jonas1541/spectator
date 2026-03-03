package com.jonasdurau.spectator.ui.broadcaster;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.domain.MarketRegime;
import com.jonasdurau.spectator.core.domain.Position;

import java.util.List;

public record MarketTick(Candle candle, MarketRegime regime, List<Position> openPositions) {
}