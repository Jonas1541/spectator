package com.jonasdurau.spectator.core.service;

import com.jonasdurau.spectator.core.domain.TradeSide;

public interface OrderExecutionService {
    void executeMarketOrder(String strategyName, String symbol, TradeSide side, double quantity, double currentPrice, Double stopLoss, Double takeProfit, Double breakevenMultiplier, Double trailingMultiplier, Double tp1Price, Double tp1SizePct);
}
