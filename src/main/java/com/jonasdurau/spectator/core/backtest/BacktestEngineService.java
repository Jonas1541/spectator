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
import com.jonasdurau.spectator.core.service.RiskManagerService;
import com.jonasdurau.spectator.core.service.HistoricalSyncService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

@Service
public class BacktestEngineService {

    private static final Logger log = LoggerFactory.getLogger(BacktestEngineService.class);

    private final CandleRepository candleRepository;
    private final RegimeAnalyzerService regimeAnalyzerService;
    private final MonteCarloSimulatorService monteCarloSimulator;
    private final RiskManagerService riskManagerService;
    private final HistoricalSyncService historicalSyncService;

    private static final int WARMUP_PERIOD = 200; 

    public BacktestEngineService(CandleRepository candleRepository, RegimeAnalyzerService regimeAnalyzerService, MonteCarloSimulatorService monteCarloSimulator, RiskManagerService riskManagerService, HistoricalSyncService historicalSyncService) {
        this.candleRepository = candleRepository;
        this.regimeAnalyzerService = regimeAnalyzerService;
        this.monteCarloSimulator = monteCarloSimulator;
        this.riskManagerService = riskManagerService;
        this.historicalSyncService = historicalSyncService;
    }

    public BacktestReport runBacktest(String executionName, List<TradingStrategy> strategies, String symbol, Instant start, Instant end, double initialCapital) {
        log.info("Starting backtest for {} on {} from {} to {}", executionName, symbol, start, end);

        // Garante que o banco de dados tem dados suficientes antes de iniciar
        historicalSyncService.ensureDataAvailable(symbol, "1h", start, end);
        historicalSyncService.ensureDataAvailable(symbol, "4h", start, end);

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
        Double currentBreakevenMultiplier = null;
        Double currentTrailingMultiplier = null;
        Double tp1Price = null;
        double tp1Quantity = 0.0;
        boolean tp1Triggered = false;
        String currentStrategyName = null;

        double grossProfit = 0.0;
        double grossLoss = 0.0;

        // Dynamic Win Rate tracking per strategy
        Map<String, Integer> strategyWins = new HashMap<>();
        Map<String, Integer> strategyLosses = new HashMap<>();
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
                    
                    double entryFee = (entryPrice * positionQuantity) * 0.0005;
                    double exitFee = (exitPrice * positionQuantity) * 0.0005;
                    double totalFee = entryFee + exitFee;
                    
                    double netPnl = grossPnl - totalFee;
                                 
                    currentCapital += netPnl;
                    tradeReturns.add(netPnl);

                    if (netPnl > 0) {
                        winningTrades++;
                        grossProfit += netPnl;
                        if (currentStrategyName != null) strategyWins.merge(currentStrategyName, 1, Integer::sum);
                    } else {
                        losingTrades++;
                        grossLoss += Math.abs(netPnl);
                        if (currentStrategyName != null) strategyLosses.merge(currentStrategyName, 1, Integer::sum);
                    }

                    if (currentCapital > peakCapital) peakCapital = currentCapital;
                    else {
                        double drawdown = ((peakCapital - currentCapital) / peakCapital) * 100;
                        if (drawdown > maxDrawdown) maxDrawdown = drawdown;
                    }

                    tradeLog.add(new BacktestTrade(currentCandle.getTime(), currentSide, false, exitPrice, netPnl));
                    inPosition = false;
                } else {
                    // ---> GESTÃO DE TRADE: Breakeven e Trailing Paramétricos <---
                    double riskDistance = Math.abs(entryPrice - initialStopLoss);
                    
                    if (currentBreakevenMultiplier != null) {
                        if (currentSide == TradeSide.LONG) {
                            if (currentCandle.getHigh() >= (entryPrice + (riskDistance * currentBreakevenMultiplier))) {
                                if (stopLoss < entryPrice) stopLoss = entryPrice;
                            }
                        } else { // SHORT
                            if (currentCandle.getLow() <= (entryPrice - (riskDistance * currentBreakevenMultiplier))) {
                                if (stopLoss > entryPrice) stopLoss = entryPrice;
                            }
                        }
                    }
                    
                    if (currentTrailingMultiplier != null) {
                        double trailingDistance = riskDistance * currentTrailingMultiplier;
                        
                        if (currentSide == TradeSide.LONG) {
                            double potentialStop = currentCandle.getHigh() - trailingDistance;
                            if (potentialStop > stopLoss) {
                                stopLoss = potentialStop; // Sobe o stop
                            }
                        } else { // SHORT
                            double potentialStop = currentCandle.getLow() + trailingDistance;
                            if (potentialStop < stopLoss) {
                                stopLoss = potentialStop; // Desce o stop
                            }
                        }
                    }
                    
                    // --- PHASE 16: PARTIAL TP1 CHECK (inside management block) ---
                    if (!tp1Triggered && tp1Price != null) {
                        boolean tp1Hit = false;
                        if (currentSide == TradeSide.LONG && currentCandle.getHigh() >= tp1Price) {
                            tp1Hit = true;
                        } else if (currentSide == TradeSide.SHORT && currentCandle.getLow() <= tp1Price) {
                            tp1Hit = true;
                        }
                        
                        if (tp1Hit) {
                            double partialPnl;
                            if (currentSide == TradeSide.LONG) {
                                partialPnl = (tp1Price - entryPrice) * tp1Quantity;
                            } else {
                                partialPnl = (entryPrice - tp1Price) * tp1Quantity;
                            }
                            double partialFees = (entryPrice * tp1Quantity * 0.0002) + (tp1Price * tp1Quantity * 0.0005);
                            partialPnl -= partialFees;
                            currentCapital += partialPnl;
                            tradeReturns.add(partialPnl);
                            if (partialPnl > 0) { winningTrades++; grossProfit += partialPnl; }
                            else { losingTrades++; grossLoss += Math.abs(partialPnl); }
                            
                            positionQuantity -= tp1Quantity;
                            tp1Triggered = true;
                            stopLoss = entryPrice; // Move stop to breakeven
                            tradeLog.add(new BacktestTrade(currentCandle.getTime(), currentSide, false, tp1Price, partialPnl));
                        }
                    }

                    // --- PHASE 16: PANIC CLOSE (HMM Regime Shift in Backtest) --- DISABLED
                    // Trade deve fechar exclusivamente por Stop Loss ou Take Profit (Tudo ou Nada).
                    // Bloco desligado para preservar a integridade do R:R planeado na entrada.
                    /*
                    if (currentStrategyName != null && currentStrategyName.toLowerCase().contains("pullback")) {
                        List<Candle> panicWindow4h = history4h.stream().filter(c -> !c.getTime().isAfter(currentCandle.getTime())).toList();
                        if (panicWindow4h.size() > 250) panicWindow4h = panicWindow4h.subList(panicWindow4h.size() - 250, panicWindow4h.size());
                        MarketRegime midTradeRegime = MarketRegime.SIDEWAYS;
                        if (panicWindow4h.size() > 50) midTradeRegime = regimeAnalyzerService.analyze(panicWindow4h);
                        
                        boolean panicClose = false;
                        if (currentSide == TradeSide.LONG && midTradeRegime != MarketRegime.TRENDING_UP) panicClose = true;
                        else if (currentSide == TradeSide.SHORT && midTradeRegime != MarketRegime.TRENDING_DOWN) panicClose = true;
                        
                        if (panicClose) {
                            double panicExitPrice = currentCandle.getClose();
                            double grossPnl = (currentSide == TradeSide.LONG) ? 
                                         (panicExitPrice - entryPrice) * positionQuantity : 
                                         (entryPrice - panicExitPrice) * positionQuantity;
                            double pEntryFee = (entryPrice * positionQuantity) * 0.0002;
                            double pExitFee = (panicExitPrice * positionQuantity) * 0.0005;
                            double netPnl = grossPnl - pEntryFee - pExitFee;
                            currentCapital += netPnl;
                            tradeReturns.add(netPnl);
                            if (netPnl > 0) {
                                winningTrades++; grossProfit += netPnl;
                                if (currentStrategyName != null) strategyWins.merge(currentStrategyName, 1, Integer::sum);
                            } else {
                                losingTrades++; grossLoss += Math.abs(netPnl);
                                if (currentStrategyName != null) strategyLosses.merge(currentStrategyName, 1, Integer::sum);
                            }
                            if (currentCapital > peakCapital) peakCapital = currentCapital;
                            else {
                                double drawdown = ((peakCapital - currentCapital) / peakCapital) * 100;
                                if (drawdown > maxDrawdown) maxDrawdown = drawdown;
                            }
                            tradeLog.add(new BacktestTrade(currentCandle.getTime(), currentSide, false, panicExitPrice, netPnl));
                            inPosition = false;
                        }
                    }
                    */
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
                signal = strategy.evaluate(window1h, regime, currentCandle.getClose(), null);
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
                initialStopLoss = stopLoss; // Guarda o risco original para calcular o 1R depois
                currentBreakevenMultiplier = signal.breakevenMultiplier();
                currentTrailingMultiplier = signal.trailingMultiplier();
                tp1Triggered = false;
                tp1Price = signal.tp1Price();
                currentStrategyName = null;

                // Resolve which strategy fired for panic close
                for (TradingStrategy s : strategies) {
                    TradeSignal check = s.evaluate(window1h, regime, currentCandle.getClose(), null);
                    if (check.fire()) {
                        currentStrategyName = s.getName();
                        break;
                    }
                }
                
                // Dynamic Win Probability calculation
                int dynWins = (currentStrategyName != null) ? strategyWins.getOrDefault(currentStrategyName, 0) : 0;
                int dynLosses = (currentStrategyName != null) ? strategyLosses.getOrDefault(currentStrategyName, 0) : 0;
                int totalClosed = dynWins + dynLosses;
                double dynamicWinProb = 0.50;
                if (totalClosed >= 5) {
                    dynamicWinProb = (double) dynWins / totalClosed;
                }

                // Fractional Kelly & Max Exposure Sizing
                double currentExposurePct = 0.0; // Backtest allows 1 active trade at a time currently
                positionQuantity = riskManagerService.calculateKellyPositionSize(
                        entryPrice,
                        stopLoss,
                        takeProfit,
                        dynamicWinProb,
                        currentCapital,
                        currentExposurePct
                );
                
                if (positionQuantity <= 0.0) {
                     inPosition = false; // Reject trade
                     continue; 
                }

                // Calculate TP1 quantity
                if (tp1Price != null && signal.tp1SizePct() != null) {
                    tp1Quantity = positionQuantity * signal.tp1SizePct();
                } else {
                    tp1Quantity = 0.0;
                }

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