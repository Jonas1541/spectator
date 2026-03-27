package com.jonasdurau.spectator.core.service;

import com.jonasdurau.spectator.core.domain.TradeSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "spectator.mode.backtest-only", havingValue = "false", matchIfMissing = true)
public class PaperTradingExecutionService implements OrderExecutionService {

    private static final Logger log = LoggerFactory.getLogger(PaperTradingExecutionService.class);
    private final PositionManagerService positionManagerService;
    private final OrderBookService orderBookService;

    public PaperTradingExecutionService(PositionManagerService positionManagerService, OrderBookService orderBookService) {
        this.positionManagerService = positionManagerService;
        this.orderBookService = orderBookService;
    }

    @Override
    public void executeMarketOrder(String strategyName, String symbol, TradeSide side, double quantity, double currentPrice, Double stopLoss,
            Double takeProfit, Double breakevenMultiplier, Double trailingMultiplier, Double tp1Price, Double tp1SizePct) {
        
        double executionPrice = currentPrice;
        
        try {
            // Flipped Spread Targeting: To post a Maker order, we align with the resting liquidity on the SAME side.
            // LONG Limit Order sits on the Bid (which is what a Market Short would hit).
            // SHORT Limit Order sits on the Ask (which is what a Market Long would hit).
            TradeSide restingLimitSide = side == TradeSide.LONG ? TradeSide.SHORT : TradeSide.LONG;
            
            double bestLimitPostingPrice = orderBookService.getBestAvailablePrice(symbol, restingLimitSide);
            
            if (bestLimitPostingPrice > 0) {
                // Assume 100% Fill Rate on the Limit posting for Paper Trading simulation.
                // Eradicates Slippage block and guarantees Maker Fee rebate execution.
                executionPrice = bestLimitPostingPrice;
                log.info("🎯 Passive Limit Order posted at best {}: {}", restingLimitSide == TradeSide.SHORT ? "BID" : "ASK", executionPrice);
            }
        } catch (IllegalStateException e) {
            // OrderBook is empty. This happens naturally during Backtesting because historical Depth is not streamed.
            log.trace("Order book snapshot unavailable. Executing at requested limit/candle close price.");
        }

        log.info("[PAPER TRADING / BACKTEST] Executing {} {} order for {} at {}", strategyName, side, symbol, executionPrice);
        com.jonasdurau.spectator.core.domain.Position position = positionManagerService.openPosition(strategyName, symbol, side, executionPrice, quantity, stopLoss, takeProfit, breakevenMultiplier, trailingMultiplier);
        
        if (tp1Price != null && tp1SizePct != null) {
            position.setTp1Price(tp1Price);
            position.setTp1Quantity(quantity * tp1SizePct);
            position.setTp1Triggered(false);
            // Position is already saved in openPosition, so we need to re-save with tp1 data
        }
    }
}
