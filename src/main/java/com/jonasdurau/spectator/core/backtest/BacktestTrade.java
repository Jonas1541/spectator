package com.jonasdurau.spectator.core.backtest;

import com.jonasdurau.spectator.core.domain.TradeSide;
import java.time.Instant;

public record BacktestTrade(
        Instant time,
        TradeSide side,
        boolean isEntry,
        double price,
        double pnl // Será 0.0 para entradas
) {}