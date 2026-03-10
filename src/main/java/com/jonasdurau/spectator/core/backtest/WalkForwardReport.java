package com.jonasdurau.spectator.core.backtest;

import java.util.List;

public record WalkForwardReport(
        int totalSlices,
        int profitableSlices,
        double consistencyScore, // Porcentagem de fatias que terminaram no verde
        List<BacktestReport> sliceReports
) {}