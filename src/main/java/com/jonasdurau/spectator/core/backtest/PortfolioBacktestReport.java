package com.jonasdurau.spectator.core.backtest;

import java.util.Map;

public record PortfolioBacktestReport(
        String executionName,
        double globalInitialCapital,
        double globalFinalCapital,
        double globalNetProfit,
        double globalMaxDrawdown,
        double globalExpectancy,
        double globalSharpeRatio,
        MonteCarloReport globalMcReport,
        Map<String, BacktestReport> symbolReports
) {
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("""
            =========================================
            PORTFOLIO BACKTEST REPORT: %s
            -----------------------------------------
            Global Initial Capital: $%.2f
            Global Final Capital:   $%.2f
            Global Net Profit:      $%.2f (%.2f%%)
            Global Max Drawdown:    %.2f%%
            Global Expectancy:      $%.2f
            Global Sharpe Ratio:    %.2f
            Risk of Ruin:           %.2f%%
            Symbols: %d
            =========================================
            """,
            executionName, globalInitialCapital, globalFinalCapital, globalNetProfit,
            (globalNetProfit / globalInitialCapital) * 100, globalMaxDrawdown,
            globalExpectancy, globalSharpeRatio, globalMcReport.riskOfRuin(),
            symbolReports.size()));
        
        for (Map.Entry<String, BacktestReport> entry : symbolReports.entrySet()) {
            sb.append(entry.getValue().toString()).append("\n");
        }
        return sb.toString();
    }
}
