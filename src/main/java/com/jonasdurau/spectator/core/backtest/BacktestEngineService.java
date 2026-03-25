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
import java.util.*;

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

    // ============================================================
    // BACKWARD-COMPATIBLE WRAPPER (single-symbol)
    // ============================================================
    public BacktestReport runBacktest(String executionName, List<TradingStrategy> strategies, String symbol, Instant start, Instant end, double initialCapital) {
        PortfolioBacktestReport portfolio = runPortfolioBacktest(executionName, strategies, List.of(symbol), start, end, initialCapital);
        return portfolio.symbolReports().get(symbol);
    }

    // ============================================================
    // MULTI-SYMBOL PORTFOLIO BACKTEST (Time Multiplexing)
    // ============================================================
    public PortfolioBacktestReport runPortfolioBacktest(String executionName, List<TradingStrategy> strategies, List<String> symbols, Instant start, Instant end, double initialCapital) {
        log.info("Starting Portfolio Backtest '{}' on {} symbols from {} to {}", executionName, symbols.size(), start, end);

        // --- PHASE 1: Data Loading ---
        Map<String, List<Candle>> history1hMap = new LinkedHashMap<>();
        Map<String, List<Candle>> history4hMap = new LinkedHashMap<>();

        for (String symbol : symbols) {
            historicalSyncService.ensureDataAvailable(symbol, "1h", start, end);
            historicalSyncService.ensureDataAvailable(symbol, "4h", start, end);

            List<Candle> h1 = candleRepository.findBySymbolAndTimeframeAndTimeBetweenOrderByTimeAsc(symbol, "1h", start, end);
            List<Candle> h4 = candleRepository.findBySymbolAndTimeframeAndTimeBetweenOrderByTimeAsc(symbol, "4h", start, end);

            if (h1.size() <= WARMUP_PERIOD) {
                log.warn("Skipping symbol {} — not enough 1H data (found: {})", symbol, h1.size());
                continue;
            }

            history1hMap.put(symbol, h1);
            history4hMap.put(symbol, h4);
            log.info("Loaded {} 1H and {} 4H candles for {}", h1.size(), h4.size(), symbol);
        }

        if (history1hMap.isEmpty()) {
            throw new IllegalStateException("No symbols had enough historical data for backtest.");
        }

        // --- PHASE 2: Build Unified Timeline ---
        TreeSet<Instant> timelineSet = new TreeSet<>();
        for (List<Candle> candles : history1hMap.values()) {
            for (int i = WARMUP_PERIOD; i < candles.size(); i++) {
                timelineSet.add(candles.get(i).getTime());
            }
        }
        List<Instant> unifiedTimeline = new ArrayList<>(timelineSet);
        log.info("Unified timeline built with {} time steps", unifiedTimeline.size());

        // --- PHASE 3: Per-Symbol State Initialization ---
        Map<String, SymbolPositionState> positionStates = new LinkedHashMap<>();
        Map<String, List<BacktestTrade>> tradeLogMap = new LinkedHashMap<>();
        Map<String, List<com.jonasdurau.spectator.core.domain.RegimeChangeEvent>> regimeChangesMap = new LinkedHashMap<>();
        Map<String, Integer> globalStrategyWins = new HashMap<>();
        Map<String, Integer> globalStrategyLosses = new HashMap<>();

        // Per-symbol accumulators for individual BacktestReports
        Map<String, SymbolAccumulator> accumulators = new LinkedHashMap<>();

        for (String symbol : history1hMap.keySet()) {
            positionStates.put(symbol, new SymbolPositionState());
            tradeLogMap.put(symbol, new ArrayList<>());
            regimeChangesMap.put(symbol, new ArrayList<>());
            accumulators.put(symbol, new SymbolAccumulator());
        }

        // --- PHASE 4: Global State ---
        double globalCapital = initialCapital;
        double globalPeakCapital = initialCapital;
        double globalMaxDrawdown = 0.0;

        // --- PHASE 5: Time-Multiplexed Core Loop ---
        for (Instant currentTime : unifiedTimeline) {

            // === STEP 1: Evaluate EXITS for all symbols ===
            for (String symbol : history1hMap.keySet()) {
                SymbolPositionState pos = positionStates.get(symbol);
                if (!pos.inPosition) continue;

                Candle currentCandle = findCandleAtTime(history1hMap.get(symbol), currentTime);
                if (currentCandle == null) continue;

                List<BacktestTrade> tradeLog = tradeLogMap.get(symbol);
                SymbolAccumulator acc = accumulators.get(symbol);

                boolean closed = false;
                double exitPrice = 0.0;

                if (pos.currentSide == TradeSide.LONG) {
                    if (currentCandle.getLow() <= pos.stopLoss) {
                        exitPrice = pos.stopLoss;
                        closed = true;
                    } else if (currentCandle.getHigh() >= pos.takeProfit) {
                        exitPrice = pos.takeProfit;
                        closed = true;
                    }
                } else { // SHORT
                    if (currentCandle.getHigh() >= pos.stopLoss) {
                        exitPrice = pos.stopLoss;
                        closed = true;
                    } else if (currentCandle.getLow() <= pos.takeProfit) {
                        exitPrice = pos.takeProfit;
                        closed = true;
                    }
                }

                if (closed) {
                    double grossPnl = (pos.currentSide == TradeSide.LONG) ?
                            (exitPrice - pos.entryPrice) * pos.positionQuantity :
                            (pos.entryPrice - exitPrice) * pos.positionQuantity;

                    double entryFee = (pos.entryPrice * pos.positionQuantity) * 0.0005;
                    double exitFee = (exitPrice * pos.positionQuantity) * 0.0005;
                    double netPnl = grossPnl - entryFee - exitFee;

                    globalCapital += netPnl;
                    acc.tradeReturns.add(netPnl);

                    if (netPnl > 0) {
                        acc.winningTrades++;
                        acc.grossProfit += netPnl;
                        if (pos.currentStrategyName != null) globalStrategyWins.merge(pos.currentStrategyName, 1, Integer::sum);
                    } else {
                        acc.losingTrades++;
                        acc.grossLoss += Math.abs(netPnl);
                        if (pos.currentStrategyName != null) globalStrategyLosses.merge(pos.currentStrategyName, 1, Integer::sum);
                    }

                    if (globalCapital > globalPeakCapital) globalPeakCapital = globalCapital;
                    else {
                        double drawdown = ((globalPeakCapital - globalCapital) / globalPeakCapital) * 100;
                        if (drawdown > globalMaxDrawdown) globalMaxDrawdown = drawdown;
                    }

                    tradeLog.add(new BacktestTrade(currentCandle.getTime(), pos.currentSide, false, exitPrice, netPnl));
                    pos.inPosition = false;
                } else {
                    // --- Trade Management: Breakeven & Trailing ---
                    double riskDistance = Math.abs(pos.entryPrice - pos.initialStopLoss);

                    if (pos.currentBreakevenMultiplier != null) {
                        if (pos.currentSide == TradeSide.LONG) {
                            if (currentCandle.getHigh() >= (pos.entryPrice + (riskDistance * pos.currentBreakevenMultiplier))) {
                                if (pos.stopLoss < pos.entryPrice) pos.stopLoss = pos.entryPrice;
                            }
                        } else {
                            if (currentCandle.getLow() <= (pos.entryPrice - (riskDistance * pos.currentBreakevenMultiplier))) {
                                if (pos.stopLoss > pos.entryPrice) pos.stopLoss = pos.entryPrice;
                            }
                        }
                    }

                    if (pos.currentTrailingMultiplier != null) {
                        double trailingDistance = riskDistance * pos.currentTrailingMultiplier;
                        if (pos.currentSide == TradeSide.LONG) {
                            double potentialStop = currentCandle.getHigh() - trailingDistance;
                            if (potentialStop > pos.stopLoss) pos.stopLoss = potentialStop;
                        } else {
                            double potentialStop = currentCandle.getLow() + trailingDistance;
                            if (potentialStop < pos.stopLoss) pos.stopLoss = potentialStop;
                        }
                    }

                    // --- Partial TP1 Check ---
                    if (!pos.tp1Triggered && pos.tp1Price != null) {
                        boolean tp1Hit = false;
                        if (pos.currentSide == TradeSide.LONG && currentCandle.getHigh() >= pos.tp1Price) tp1Hit = true;
                        else if (pos.currentSide == TradeSide.SHORT && currentCandle.getLow() <= pos.tp1Price) tp1Hit = true;

                        if (tp1Hit) {
                            double partialPnl;
                            if (pos.currentSide == TradeSide.LONG) {
                                partialPnl = (pos.tp1Price - pos.entryPrice) * pos.tp1Quantity;
                            } else {
                                partialPnl = (pos.entryPrice - pos.tp1Price) * pos.tp1Quantity;
                            }
                            double partialFees = (pos.entryPrice * pos.tp1Quantity * 0.0002) + (pos.tp1Price * pos.tp1Quantity * 0.0005);
                            partialPnl -= partialFees;
                            globalCapital += partialPnl;
                            acc.tradeReturns.add(partialPnl);
                            if (partialPnl > 0) { acc.winningTrades++; acc.grossProfit += partialPnl; }
                            else { acc.losingTrades++; acc.grossLoss += Math.abs(partialPnl); }

                            pos.positionQuantity -= pos.tp1Quantity;
                            pos.tp1Triggered = true;
                            pos.stopLoss = pos.entryPrice;
                            tradeLog.add(new BacktestTrade(currentCandle.getTime(), pos.currentSide, false, pos.tp1Price, partialPnl));
                        }
                    }
                }
            }

            // === STEP 2: Calculate Current Exposure ===
            double totalMarginInUse = 0.0;
            for (SymbolPositionState pos : positionStates.values()) {
                if (pos.inPosition) {
                    totalMarginInUse += pos.entryPrice * pos.positionQuantity;
                }
            }
            double currentExposurePct = (globalCapital > 0) ? totalMarginInUse / globalCapital : 1.0;

            // === STEP 3: Evaluate ENTRIES for all symbols ===
            for (String symbol : history1hMap.keySet()) {
                SymbolPositionState pos = positionStates.get(symbol);
                if (pos.inPosition) continue;

                List<Candle> history1h = history1hMap.get(symbol);
                List<Candle> history4h = history4hMap.get(symbol);
                List<BacktestTrade> tradeLog = tradeLogMap.get(symbol);
                List<com.jonasdurau.spectator.core.domain.RegimeChangeEvent> regimeChanges = regimeChangesMap.get(symbol);

                int candleIndex = findCandleIndex(history1h, currentTime);
                if (candleIndex < WARMUP_PERIOD) continue;

                Candle currentCandle = history1h.get(candleIndex);

                // Build windows
                List<Candle> window1h = history1h.subList(candleIndex - WARMUP_PERIOD, candleIndex + 1);
                List<Candle> window4h = history4h.stream().filter(c -> !c.getTime().isAfter(currentCandle.getTime())).toList();

                MarketRegime regime = MarketRegime.SIDEWAYS;
                if (window4h.size() > 250) window4h = window4h.subList(window4h.size() - 250, window4h.size());
                if (window4h.size() > 50) regime = regimeAnalyzerService.analyze(window4h);

                if (pos.lastRegime != null && regime != pos.lastRegime) {
                    regimeChanges.add(new com.jonasdurau.spectator.core.domain.RegimeChangeEvent(currentCandle.getTime(), regime));
                }
                pos.lastRegime = regime;

                TradeSignal signal = TradeSignal.ignore();
                for (TradingStrategy strategy : strategies) {
                    signal = strategy.evaluate(window1h, regime, currentCandle.getClose(), null);
                    if (signal.fire()) break;
                }

                if (signal.fire()) {
                    pos.inPosition = true;
                    pos.currentSide = signal.side();
                    pos.entryPrice = currentCandle.getClose();
                    pos.stopLoss = signal.stopLoss();
                    pos.takeProfit = signal.takeProfit();
                    pos.initialStopLoss = pos.stopLoss;
                    pos.currentBreakevenMultiplier = signal.breakevenMultiplier();
                    pos.currentTrailingMultiplier = signal.trailingMultiplier();
                    pos.tp1Triggered = false;
                    pos.tp1Price = signal.tp1Price();
                    pos.currentStrategyName = null;

                    // Resolve which strategy fired
                    for (TradingStrategy s : strategies) {
                        TradeSignal check = s.evaluate(window1h, regime, currentCandle.getClose(), null);
                        if (check.fire()) {
                            pos.currentStrategyName = s.getName();
                            break;
                        }
                    }

                    // Dynamic Win Probability
                    int dynWins = (pos.currentStrategyName != null) ? globalStrategyWins.getOrDefault(pos.currentStrategyName, 0) : 0;
                    int dynLosses = (pos.currentStrategyName != null) ? globalStrategyLosses.getOrDefault(pos.currentStrategyName, 0) : 0;
                    int totalClosed = dynWins + dynLosses;
                    double dynamicWinProb = 0.50;
                    if (totalClosed >= 5) {
                        dynamicWinProb = (double) dynWins / totalClosed;
                    }

                    // Kelly Sizing with global exposure
                    pos.positionQuantity = riskManagerService.calculateKellyPositionSize(
                            pos.entryPrice,
                            pos.stopLoss,
                            pos.takeProfit,
                            dynamicWinProb,
                            globalCapital,
                            currentExposurePct
                    );

                    if (pos.positionQuantity <= 0.0) {
                        pos.inPosition = false;
                        continue;
                    }

                    // Recalculate exposure after this entry
                    currentExposurePct = recalculateExposure(positionStates, globalCapital);

                    // Calculate TP1 quantity
                    if (pos.tp1Price != null && signal.tp1SizePct() != null) {
                        pos.tp1Quantity = pos.positionQuantity * signal.tp1SizePct();
                    } else {
                        pos.tp1Quantity = 0.0;
                    }

                    tradeLog.add(new BacktestTrade(currentCandle.getTime(), pos.currentSide, true, pos.entryPrice, 0.0));
                }
            }
        }

        // --- PHASE 6: Build per-symbol BacktestReports ---
        Map<String, BacktestReport> symbolReports = new LinkedHashMap<>();

        for (String symbol : history1hMap.keySet()) {
            SymbolAccumulator acc = accumulators.get(symbol);
            List<BacktestTrade> tradeLog = tradeLogMap.get(symbol);
            List<com.jonasdurau.spectator.core.domain.RegimeChangeEvent> regimeChanges = regimeChangesMap.get(symbol);

            int totalTrades = acc.winningTrades + acc.losingTrades;
            double winRate = totalTrades > 0 ? ((double) acc.winningTrades / totalTrades) : 0.0;
            double lossRate = 1.0 - winRate;
            double symbolNetProfit = acc.grossProfit - acc.grossLoss;

            double avgWin = acc.winningTrades > 0 ? (acc.grossProfit / acc.winningTrades) : 0.0;
            double avgLoss = acc.losingTrades > 0 ? (acc.grossLoss / acc.losingTrades) : 0.0;
            double expectancy = (winRate * avgWin) - (lossRate * avgLoss);

            double sharpeRatio = 0.0;
            if (totalTrades > 1) {
                double meanReturn = acc.tradeReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                double variance = acc.tradeReturns.stream()
                        .mapToDouble(r -> Math.pow(r - meanReturn, 2))
                        .sum() / (totalTrades - 1);
                double stdDev = Math.sqrt(variance);
                if (stdDev > 0) {
                    sharpeRatio = (meanReturn / stdDev) * Math.sqrt(totalTrades);
                }
            }

            // Per-symbol max drawdown (from trade returns)
            double symbolPeak = initialCapital;
            double symbolCapital = initialCapital;
            double symbolMaxDD = 0.0;
            for (double ret : acc.tradeReturns) {
                symbolCapital += ret;
                if (symbolCapital > symbolPeak) symbolPeak = symbolCapital;
                else {
                    double dd = ((symbolPeak - symbolCapital) / symbolPeak) * 100;
                    if (dd > symbolMaxDD) symbolMaxDD = dd;
                }
            }

            MonteCarloReport mcReport = monteCarloSimulator.runSimulation(tradeLog, initialCapital);

            BacktestReport report = new BacktestReport(
                    executionName, symbol, start, end, totalTrades, acc.winningTrades, acc.losingTrades,
                    winRate * 100, symbolNetProfit, symbolMaxDD, expectancy, sharpeRatio,
                    initialCapital, initialCapital + symbolNetProfit, tradeLog, regimeChanges, mcReport
            );
            symbolReports.put(symbol, report);
        }

        double globalNetProfit = globalCapital - initialCapital;
        log.info("Portfolio Backtest '{}' complete. Global Net Profit: ${}", executionName, String.format("%.2f", globalNetProfit));

        return new PortfolioBacktestReport(
                executionName,
                initialCapital,
                globalCapital,
                globalNetProfit,
                globalMaxDrawdown,
                symbolReports
        );
    }

    // ============================================================
    // HELPER: Find the candle at an exact timestamp
    // ============================================================
    private Candle findCandleAtTime(List<Candle> candles, Instant time) {
        int idx = findCandleIndex(candles, time);
        return idx >= 0 ? candles.get(idx) : null;
    }

    private int findCandleIndex(List<Candle> candles, Instant time) {
        // Binary search for performance on large lists
        int low = 0, high = candles.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int cmp = candles.get(mid).getTime().compareTo(time);
            if (cmp < 0) low = mid + 1;
            else if (cmp > 0) high = mid - 1;
            else return mid;
        }
        return -1;
    }

    private double recalculateExposure(Map<String, SymbolPositionState> positionStates, double globalCapital) {
        if (globalCapital <= 0) return 1.0;
        double totalMargin = 0.0;
        for (SymbolPositionState pos : positionStates.values()) {
            if (pos.inPosition) {
                totalMargin += pos.entryPrice * pos.positionQuantity;
            }
        }
        return totalMargin / globalCapital;
    }

    // ============================================================
    // INNER CLASS: Mutable position state per symbol
    // ============================================================
    private static class SymbolPositionState {
        boolean inPosition = false;
        TradeSide currentSide = null;
        double entryPrice = 0.0;
        double positionQuantity = 0.0;
        double stopLoss = 0.0;
        double takeProfit = 0.0;
        double initialStopLoss = 0.0;
        Double currentBreakevenMultiplier = null;
        Double currentTrailingMultiplier = null;
        Double tp1Price = null;
        double tp1Quantity = 0.0;
        boolean tp1Triggered = false;
        String currentStrategyName = null;
        MarketRegime lastRegime = null;
    }

    // ============================================================
    // INNER CLASS: Per-symbol trade accumulators
    // ============================================================
    private static class SymbolAccumulator {
        int winningTrades = 0;
        int losingTrades = 0;
        double grossProfit = 0.0;
        double grossLoss = 0.0;
        List<Double> tradeReturns = new ArrayList<>();
    }
}