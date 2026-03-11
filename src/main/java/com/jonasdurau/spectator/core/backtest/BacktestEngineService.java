package com.jonasdurau.spectator.core.backtest;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.domain.MarketRegime;
import com.jonasdurau.spectator.core.domain.TradeSide;
import com.jonasdurau.spectator.core.repository.CandleRepository;
import com.jonasdurau.spectator.core.strategy.RegimeAnalyzerService;
import com.jonasdurau.spectator.core.strategy.TradeSignal;
import com.jonasdurau.spectator.core.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class BacktestEngineService {

    private static final Logger log = LoggerFactory.getLogger(BacktestEngineService.class);

    private final CandleRepository candleRepository;
    private final RegimeAnalyzerService regimeAnalyzerService;
    private final MonteCarloSimulatorService monteCarloSimulator;

    private static final int WARMUP_PERIOD = 200; 

    public BacktestEngineService(CandleRepository candleRepository, RegimeAnalyzerService regimeAnalyzerService, MonteCarloSimulatorService monteCarloSimulator) {
        this.candleRepository = candleRepository;
        this.regimeAnalyzerService = regimeAnalyzerService;
        this.monteCarloSimulator = monteCarloSimulator;
    }

    public BacktestReport runBacktest(String executionName, List<TradingStrategy> strategies, String symbol, Instant start, Instant end, double initialCapital) {
        log.info("Starting backtest for {} on {} from {} to {}", executionName, symbol, start, end);

        List<Candle> history1h = candleRepository.findBySymbolAndTimeframeAndTimeBetweenOrderByTimeAsc(symbol, "1h", start, end);
        List<Candle> history4h = candleRepository.findBySymbolAndTimeframeAndTimeBetweenOrderByTimeAsc(symbol, "4h", start, end);

        if (history1h.size() <= WARMUP_PERIOD) {
            throw new IllegalStateException("Not enough historical data for backtest. Found: " + history1h.size());
        }

        double currentCapital = initialCapital;
        double peakCapital = initialCapital;
        double maxDrawdown = 0.0;
        int winningTrades = 0;
        int losingTrades = 0;
        double initialStopLoss = 0.0;
        
        List<BacktestTrade> tradeLog = new ArrayList<>();
        List<com.jonasdurau.spectator.core.domain.RegimeChangeEvent> regimeChanges = new ArrayList<>();

        boolean inPosition = false;
        TradeSide currentSide = null;
        double entryPrice = 0.0;
        double positionQuantity = 0.0;
        double stopLoss = 0.0;
        double takeProfit = 0.0;
        MarketRegime lastRegime = null;

        double grossProfit = 0.0;
        double grossLoss = 0.0;
        List<Double> tradeReturns = new java.util.ArrayList<>(); 

        for (int i = WARMUP_PERIOD; i < history1h.size(); i++) {
            Candle currentCandle = history1h.get(i);

            // --- AVALIAÇÃO DE SAÍDA ---
            if (inPosition) {
                boolean closed = false;
                double exitPrice = 0.0;

                if (currentSide == TradeSide.LONG) {
                    if (currentCandle.getLow() <= stopLoss) {
                        exitPrice = stopLoss;
                        closed = true;
                    } else if (currentCandle.getHigh() >= takeProfit) {
                        exitPrice = takeProfit;
                        closed = true;
                    }
                } else { // SHORT
                    if (currentCandle.getHigh() >= stopLoss) {
                        exitPrice = stopLoss;
                        closed = true;
                    } else if (currentCandle.getLow() <= takeProfit) {
                        exitPrice = takeProfit;
                        closed = true;
                    }
                }

                if (closed) {
                    double grossPnl = (currentSide == TradeSide.LONG) ? 
                                 (exitPrice - entryPrice) * positionQuantity : 
                                 (entryPrice - exitPrice) * positionQuantity;
                    
                    double entryFee = (entryPrice * positionQuantity) * 0.001;
                    double exitFee = (exitPrice * positionQuantity) * 0.001;
                    double totalFee = entryFee + exitFee;
                    
                    double netPnl = grossPnl - totalFee;
                                 
                    currentCapital += netPnl;
                    tradeReturns.add(netPnl);

                    if (netPnl > 0) {
                        winningTrades++;
                        grossProfit += netPnl;
                    } else {
                        losingTrades++;
                        grossLoss += Math.abs(netPnl);
                    }

                    if (currentCapital > peakCapital) peakCapital = currentCapital;
                    else {
                        double drawdown = ((peakCapital - currentCapital) / peakCapital) * 100;
                        if (drawdown > maxDrawdown) maxDrawdown = drawdown;
                    }

                    tradeLog.add(new BacktestTrade(currentCandle.getTime(), currentSide, false, exitPrice, netPnl));
                    inPosition = false;
                } else {
                    // ---> GESTÃO DE TRADE: 2R Breakeven "Set & Forget" <---
                    double riskDistance = Math.abs(entryPrice - initialStopLoss);
                    
                    if (currentSide == TradeSide.LONG) {
                        // Se o preço subiu 2x a distância do nosso risco inicial
                        if (currentCandle.getHigh() >= (entryPrice + (riskDistance * 2.0))) {
                            if (stopLoss < entryPrice) stopLoss = entryPrice; // Move pro zero-a-zero
                        }
                    } else { // SHORT
                        // Se o preço caiu 2x a distância do nosso risco inicial
                        if (currentCandle.getLow() <= (entryPrice - (riskDistance * 2.0))) {
                            if (stopLoss > entryPrice) stopLoss = entryPrice; // Move pro zero-a-zero
                        }
                    }
                }
                continue;
            }

            // --- AVALIAÇÃO DE ENTRADA ---
            List<Candle> window1h = history1h.subList(i - WARMUP_PERIOD, i + 1);
            List<Candle> window4h = history4h.stream().filter(c -> !c.getTime().isAfter(currentCandle.getTime())).toList();

            MarketRegime regime = MarketRegime.SIDEWAYS;
            if (window4h.size() > 250) window4h = window4h.subList(window4h.size() - 250, window4h.size());
            if (window4h.size() > 50) regime = regimeAnalyzerService.analyze(window4h);

            if (lastRegime != null && regime != lastRegime) {
                regimeChanges.add(new com.jonasdurau.spectator.core.domain.RegimeChangeEvent(currentCandle.getTime(), regime));
            }
            lastRegime = regime;

            TradeSignal signal = TradeSignal.ignore();
            for (TradingStrategy strategy : strategies) {
                signal = strategy.evaluate(window1h, regime, currentCandle.getClose());
                if (signal.fire()) {
                    break; 
                }
            }

            if (signal.fire()) {
                inPosition = true;
                currentSide = signal.side();
                entryPrice = currentCandle.getClose();
                stopLoss = signal.stopLoss();
                takeProfit = signal.takeProfit();
                stopLoss = signal.stopLoss();
                initialStopLoss = stopLoss; // Guarda o risco original para calcular o 1R depois
                
                // Risco Profissional Limitado a 0.25%
                double riskAmount = currentCapital * 0.0025;
                double stopDistance = Math.abs(entryPrice - stopLoss);
                positionQuantity = riskAmount / stopDistance;

                tradeLog.add(new BacktestTrade(currentCandle.getTime(), currentSide, true, entryPrice, 0.0));
            }
        }

        int totalTrades = winningTrades + losingTrades;
        double winRate = totalTrades > 0 ? ((double) winningTrades / totalTrades) : 0.0;
        double lossRate = 1.0 - winRate;
        double netProfit = currentCapital - initialCapital;

        double avgWin = winningTrades > 0 ? (grossProfit / winningTrades) : 0.0;
        double avgLoss = losingTrades > 0 ? (grossLoss / losingTrades) : 0.0;
        double expectancy = (winRate * avgWin) - (lossRate * avgLoss);

        double sharpeRatio = 0.0;
        if (totalTrades > 1) {
            double meanReturn = tradeReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double variance = tradeReturns.stream()
                .mapToDouble(r -> Math.pow(r - meanReturn, 2))
                .sum() / (totalTrades - 1);
            double stdDev = Math.sqrt(variance);
            
            if (stdDev > 0) {
                sharpeRatio = (meanReturn / stdDev) * Math.sqrt(totalTrades); 
            }
        }

        MonteCarloReport mcReport = monteCarloSimulator.runSimulation(tradeLog, initialCapital);

        return new BacktestReport(
                executionName, symbol, start, end, totalTrades, winningTrades, losingTrades, 
                winRate * 100, netProfit, maxDrawdown, expectancy, sharpeRatio,
                initialCapital, currentCapital, tradeLog, regimeChanges, mcReport
        );
    }
}