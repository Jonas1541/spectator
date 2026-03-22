package com.jonasdurau.spectator.core.strategy;

import com.jonasdurau.spectator.core.domain.TradeSide;

public record TradeSignal(
        boolean fire,
        TradeSide side,
        double quantity,
        Double stopLoss,
        Double takeProfit,
        Double breakevenMultiplier,
        Double trailingMultiplier
) {
    public static TradeSignal ignore() {
        return new TradeSignal(false, null, 0, null, null, null, null);
    }

    public static TradeSignal enter(TradeSide side, double quantity, Double stopLoss, Double takeProfit, Double breakevenMultiplier, Double trailingMultiplier) {
        return new TradeSignal(true, side, quantity, stopLoss, takeProfit, breakevenMultiplier, trailingMultiplier);
    }
}
