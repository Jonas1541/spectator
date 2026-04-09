package com.jonasdurau.spectator.core.service;

/**
 * Métricas de performance calculadas a partir de posições fechadas no banco de dados.
 * Usado pelo LiveMetricsService para alimentar o dashboard em tempo real.
 * Apenas cálculos leves (aritmética sobre lista de trades), sem Monte Carlo.
 */
public record LiveSymbolMetrics(
        int activeTrades,
        int totalTrades,
        int wins,
        int losses,
        double winRate,
        double netProfit,
        double maxDrawdown,
        double expectancy,
        double sharpeRatio
) {
    public static LiveSymbolMetrics empty() {
        return new LiveSymbolMetrics(0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }
}
