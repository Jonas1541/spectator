package com.jonasdurau.spectator.core.service;

import com.jonasdurau.spectator.core.domain.TradeSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PaperTradingExecutionService implements OrderExecutionService {

    private static final Logger log = LoggerFactory.getLogger(PaperTradingExecutionService.class);
    private final PositionManagerService positionManagerService;

    public PaperTradingExecutionService(PositionManagerService positionManagerService) {
        this.positionManagerService = positionManagerService;
    }

    @Override
    public void executeMarketOrder(String symbol, TradeSide side, double quantity, double currentPrice, Double stopLoss,
            Double takeProfit) {
        log.info("[PAPER TRADING] Executing {} order for {} at {}", side, symbol, currentPrice);
        positionManagerService.openPosition(symbol, side, currentPrice, quantity, stopLoss, takeProfit);
    }
}
