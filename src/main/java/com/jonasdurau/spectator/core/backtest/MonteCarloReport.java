package com.jonasdurau.spectator.core.backtest;

public record MonteCarloReport(
        int simulationsRun,
        double riskOfRuin, // % de simulações que quebraram a conta (excederam o limite de Drawdown)
        double medianMaxDrawdown, // O Drawdown médio nas realidades alternativas
        double ruinThresholdPercentage // A linha vermelha (ex: 20% de perda)
) {}