package com.jonasdurau.spectator.core.strategy;

import com.jonasdurau.spectator.core.domain.TradeSide;

public record TradeSignal(
        boolean fire,
        TradeSide side,
        Double stopLoss,
        Double takeProfit,
        Double breakevenMultiplier,
        Double trailingMultiplier,
        Double winProbability
) {
    public static TradeSignal ignore() {
        return new TradeSignal(false, null, null, null, null, null, null);
    }

    public static TradeSignal enter(TradeSide side, Double stopLoss, Double takeProfit, Double breakevenMultiplier, Double trailingMultiplier, Double winProbability) {
        return new TradeSignal(true, side, stopLoss, takeProfit, breakevenMultiplier, trailingMultiplier, winProbability);
    }
}
