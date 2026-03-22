package com.jonasdurau.spectator.core.service;

import com.jonasdurau.spectator.core.domain.TradeSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PaperTradingExecutionService implements OrderExecutionService {

    private static final Logger log = LoggerFactory.getLogger(PaperTradingExecutionService.class);
    private final PositionManagerService positionManagerService;
    private final OrderBookService orderBookService;

    public PaperTradingExecutionService(PositionManagerService positionManagerService, OrderBookService orderBookService) {
        this.positionManagerService = positionManagerService;
        this.orderBookService = orderBookService;
    }

    @Override
    public void executeMarketOrder(String symbol, TradeSide side, double quantity, double currentPrice, Double stopLoss,
            Double takeProfit, Double breakevenMultiplier, Double trailingMultiplier) {
        
        double executionPrice = currentPrice;
        
        try {
            double bestPrice = orderBookService.getBestAvailablePrice(side);
            if (bestPrice > 0) {
                double expectedFill = orderBookService.calculateExpectedFillPrice(side, quantity);
                
                // Slippage Formula: Difference between Best available price and VWAP of partial fills
                double slippage = Math.abs(expectedFill - bestPrice) / bestPrice;
                
                // Slippage Guard: Reject orders that would suffer > 5 basis points of slippage (Toxic Liquidity)
                if (slippage > 0.0005) {
                    log.warn("🚨 TOXIC LIQUIDITY AVOIDED 🚨 {} Market Order of {} units rejected. Slippage: {} bps. Expected Fill VWAP: {}, Best Price: {}", 
                            side, quantity, String.format("%.2f", slippage * 10000), expectedFill, bestPrice);
                    return; // Abort Execution
                }
                
                executionPrice = expectedFill; // Force Paper Trading to execute at realistic slippage VWAP!
            }
        } catch (IllegalStateException e) {
            // OrderBook is empty. This happens naturally during Backtesting because historical Depth is not streamed.
            log.trace("Order book snapshot unavailable. Executing at requested limit/candle close price.");
        }

        log.info("[PAPER TRADING / BACKTEST] Executing {} order for {} at {}", side, symbol, executionPrice);
        positionManagerService.openPosition(symbol, side, executionPrice, quantity, stopLoss, takeProfit, breakevenMultiplier, trailingMultiplier);
    }
}
