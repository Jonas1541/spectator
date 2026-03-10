package com.jonasdurau.spectator.core.backtest;

import com.jonasdurau.spectator.core.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class WalkForwardAnalyzerService {

    private static final Logger log = LoggerFactory.getLogger(WalkForwardAnalyzerService.class);
    private final BacktestEngineService backtestEngine;

    public WalkForwardAnalyzerService(BacktestEngineService backtestEngine) {
        this.backtestEngine = backtestEngine;
    }

    public WalkForwardReport runAnalysis(String executionName, List<TradingStrategy> strategies, 
                                         String symbol, Instant start, Instant end, 
                                         double initialCapital, int slices) {
        
        log.info("Starting Walk-Forward Analysis ({} slices) from {} to {}", slices, start, end);

        long totalMillis = end.toEpochMilli() - start.toEpochMilli();
        long sliceMillis = totalMillis / slices;

        List<BacktestReport> reports = new ArrayList<>();
        int profitableSlices = 0;

        for (int i = 0; i < slices; i++) {
            Instant sliceStart = start.plusMillis(sliceMillis * i);
            Instant sliceEnd = (i == slices - 1) ? end : sliceStart.plusMillis(sliceMillis);

            String sliceName = String.format("Slice %d/%d", i + 1, slices);
            
            // Roda o backtest isolado para este pedaço de tempo
            BacktestReport report = backtestEngine.runBacktest(
                    sliceName, strategies, symbol, sliceStart, sliceEnd, initialCapital
            );
            
            reports.add(report);
            
            if (report.netProfit() > 0) {
                profitableSlices++;
            }
        }

        double consistency = ((double) profitableSlices / slices) * 100;
        return new WalkForwardReport(slices, profitableSlices, consistency, reports);
    }
}