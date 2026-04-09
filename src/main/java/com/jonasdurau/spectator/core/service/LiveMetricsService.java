package com.jonasdurau.spectator.core.service;

import com.jonasdurau.spectator.core.domain.Position;
import com.jonasdurau.spectator.core.domain.PositionStatus;
import com.jonasdurau.spectator.core.repository.PositionRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Serviço responsável por calcular métricas de performance leves
 * a partir de posições fechadas no banco de dados.
 * Satisfaz SRP: isolado da lógica de UI e do motor de trading.
 * Não executa simulações pesadas (Monte Carlo, Walk-Forward).
 */
@Service
public class LiveMetricsService {

    private final PositionRepository positionRepository;

    public LiveMetricsService(PositionRepository positionRepository) {
        this.positionRepository = positionRepository;
    }

    /**
     * Calcula métricas para um símbolo específico.
     */
    public LiveSymbolMetrics computeMetrics(String symbol) {
        List<Position> closedPositions = positionRepository
                .findBySymbolAndStatusOrderByClosedAtAsc(symbol, PositionStatus.CLOSED);
        return buildMetrics(closedPositions);
    }

    /**
     * Calcula métricas globais (todos os símbolos agregados).
     */
    public LiveSymbolMetrics computeGlobalMetrics() {
        List<Position> closedPositions = positionRepository
                .findByStatusOrderByClosedAtAsc(PositionStatus.CLOSED);
        return buildMetrics(closedPositions);
    }

    private LiveSymbolMetrics buildMetrics(List<Position> closedPositions) {
        if (closedPositions.isEmpty()) {
            return LiveSymbolMetrics.empty();
        }

        int totalTrades = closedPositions.size();
        int wins = 0;
        int losses = 0;
        double totalProfit = 0.0;
        double totalWinAmount = 0.0;
        double totalLossAmount = 0.0;

        // Vetores para Sharpe e Drawdown
        double[] returns = new double[totalTrades];
        double cumulativePnl = 0.0;
        double peakPnl = 0.0;
        double maxDrawdown = 0.0;

        for (int i = 0; i < totalTrades; i++) {
            Position p = closedPositions.get(i);
            double pnl = p.getRealizedPnl() != null ? p.getRealizedPnl() : 0.0;

            if (pnl > 0) {
                wins++;
                totalWinAmount += pnl;
            } else {
                losses++;
                totalLossAmount += Math.abs(pnl);
            }

            totalProfit += pnl;
            returns[i] = pnl;

            // Curva de PnL acumulado para drawdown
            cumulativePnl += pnl;
            if (cumulativePnl > peakPnl) {
                peakPnl = cumulativePnl;
            }
            double drawdown = peakPnl > 0 ? ((peakPnl - cumulativePnl) / peakPnl) * 100.0 : 0.0;
            if (drawdown > maxDrawdown) {
                maxDrawdown = drawdown;
            }
        }

        double winRate = totalTrades > 0 ? ((double) wins / totalTrades) * 100.0 : 0.0;

        // Expectancy = (avgWin × winProb) − (avgLoss × lossProb)
        double avgWin = wins > 0 ? totalWinAmount / wins : 0.0;
        double avgLoss = losses > 0 ? totalLossAmount / losses : 0.0;
        double winProb = totalTrades > 0 ? (double) wins / totalTrades : 0.0;
        double lossProb = totalTrades > 0 ? (double) losses / totalTrades : 0.0;
        double expectancy = (avgWin * winProb) - (avgLoss * lossProb);

        // Sharpe Ratio = mean(returns) / stddev(returns) × √252
        double sharpeRatio = computeSharpeRatio(returns);

        return new LiveSymbolMetrics(totalTrades, wins, losses, winRate, totalProfit,
                maxDrawdown, expectancy, sharpeRatio);
    }

    private double computeSharpeRatio(double[] returns) {
        if (returns.length < 2) {
            return 0.0;
        }

        double sum = 0.0;
        for (double r : returns) {
            sum += r;
        }
        double mean = sum / returns.length;

        double varianceSum = 0.0;
        for (double r : returns) {
            varianceSum += (r - mean) * (r - mean);
        }
        double stddev = Math.sqrt(varianceSum / (returns.length - 1));

        if (stddev == 0.0) {
            return 0.0;
        }

        // Anualizado com √252 (dias de trading por ano)
        return (mean / stddev) * Math.sqrt(252);
    }
}
