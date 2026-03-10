package com.jonasdurau.spectator.core.backtest;

import java.util.List;

import com.jonasdurau.spectator.core.domain.RegimeChangeEvent;

public record BacktestReport(
        String strategyName,
        String symbol,
        int totalTrades,
        int winningTrades,
        int losingTrades,
        double winRate,
        double netProfit,
        double maxDrawdown,
        double expectancy,
        double sharpeRatio,
        double initialCapital,
        double finalCapital,
        List<BacktestTrade> tradeLog,
        List<RegimeChangeEvent> regimeChanges,
        MonteCarloReport monteCarlo
) {
    @Override
    public String toString() {
        return String.format("""
            =========================================
            BACKTEST REPORT: %s
            Symbol: %s
            -----------------------------------------
            Initial Capital: $%.2f
            Final Capital:   $%.2f
            Net Profit:      $%.2f (%.2f%%)
            Max Drawdown:    %.2f%%
            -----------------------------------------
            Total Trades:    %d
            Win Rate:        %.2f%% (%d W / %d L)
            =========================================
            """,
            strategyName, symbol, initialCapital, finalCapital, netProfit,
            (netProfit / initialCapital) * 100, maxDrawdown, expectancy,
            sharpeRatio, totalTrades, winRate, winningTrades, losingTrades);
    }
}