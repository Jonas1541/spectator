package com.jonasdurau.spectator.core.backtest;

import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

@Service
public class MonteCarloSimulatorService {

    private static final int SIMULATIONS = 2000;
    private static final double RUIN_THRESHOLD = 20.0; // Se o Drawdown passar de 20%, consideramos ruína

    public MonteCarloReport runSimulation(List<BacktestTrade> tradeLog, double initialCapital) {
        // Extrai trades fechados e os ordena cronologicamente
        List<BacktestTrade> closedTrades = tradeLog.stream()
                .filter(t -> !t.isEntry())
                .sorted(java.util.Comparator.comparing(BacktestTrade::time))
                .toList();

        if (closedTrades.isEmpty()) {
            return new MonteCarloReport(0, 0.0, 0.0, RUIN_THRESHOLD);
        }

        // Reconstrói a curva de capital real para encontrar o ROI % exato de cada trade
        List<Double> tradeRois = new ArrayList<>();
        double historyEquity = initialCapital;

        for (BacktestTrade trade : closedTrades) {
            double pnl = trade.pnl();
            double returnPct = pnl / historyEquity;
            tradeRois.add(returnPct);
            historyEquity += pnl;
        }

        int ruinCount = 0;
        List<Double> maxDrawdowns = new ArrayList<>();
        Random random = new Random();
        int tradeCount = tradeRois.size();

        for (int i = 0; i < SIMULATIONS; i++) {
            double simulatedCapital = initialCapital;
            double peakCapital = initialCapital;
            double maxDd = 0.0;
            boolean ruined = false;

            // Simula uma linha do tempo inteira sorteando trades do passado aleatoriamente
            for (int j = 0; j < tradeCount; j++) {
                double randomTradeRoi = tradeRois.get(random.nextInt(tradeCount));
                simulatedCapital *= (1 + randomTradeRoi);

                if (simulatedCapital > peakCapital) {
                    peakCapital = simulatedCapital;
                } else {
                    double dd = ((peakCapital - simulatedCapital) / peakCapital) * 100;
                    if (dd > maxDd) {
                        maxDd = dd;
                    }
                }

                if (maxDd >= RUIN_THRESHOLD) {
                    ruined = true;
                }
            }
            
            if (ruined) {
                ruinCount++;
            }
            maxDrawdowns.add(maxDd);
        }

        // Calcula a mediana do Drawdown em todas as simulações
        Collections.sort(maxDrawdowns);
        double medianDd = maxDrawdowns.get(maxDrawdowns.size() / 2);
        double riskOfRuin = ((double) ruinCount / SIMULATIONS) * 100;

        return new MonteCarloReport(SIMULATIONS, riskOfRuin, medianDd, RUIN_THRESHOLD);
    }
}