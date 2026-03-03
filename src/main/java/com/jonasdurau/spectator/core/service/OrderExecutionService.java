package com.jonasdurau.spectator.core.service;

import com.jonasdurau.spectator.core.domain.TradeSide;

public interface OrderExecutionService {
    void executeMarketOrder(String symbol, TradeSide side, double quantity, double currentPrice, Double stopLoss, Double takeProfit);
}
