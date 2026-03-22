package com.jonasdurau.spectator.core.service;

import com.jonasdurau.spectator.core.domain.TradeSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class OrderBookService {

    private static final Logger log = LoggerFactory.getLogger(OrderBookService.class);

    public record PriceLevel(double price, double quantity) {}

    // Atomic references to allow lock-free reads from the strategy threads while the WebSocket thread updates them
    private volatile List<PriceLevel> bestBids = new ArrayList<>();
    private volatile List<PriceLevel> bestAsks = new ArrayList<>();

    public void updateOrderBook(List<PriceLevel> newBids, List<PriceLevel> newAsks) {
        this.bestBids = new ArrayList<>(newBids);
        this.bestAsks = new ArrayList<>(newAsks);
    }

    /**
     * Calculates the Volume Weighted Average Price (VWAP) if we send a Market Order of the given quantity.
     * Throws an Exception if the depth is insufficient or execution would slip beyond acceptable levels natively.
     */
    public double calculateExpectedFillPrice(TradeSide side, double targetQuantity) throws IllegalStateException {
        List<PriceLevel> levels = side == TradeSide.LONG ? bestAsks : bestBids;

        if (levels == null || levels.isEmpty()) {
            throw new IllegalStateException("Order Book memory is empty. Awaiting WebSocket stream...");
        }

        double remainingQuantity = targetQuantity;
        double totalCost = 0.0;
        
        // Loop through the best available levels (Asks for Long/Buy, Bids for Short/Sell)
        for (PriceLevel level : levels) {
            double fillQty = Math.min(level.quantity(), remainingQuantity);
            totalCost += (level.price() * fillQty);
            remainingQuantity -= fillQty;

            if (remainingQuantity <= 0) {
                break;
            }
        }

        if (remainingQuantity > 0) {
            log.warn("Not enough volume in top 5 levels to fill {} units. Remaining {}", targetQuantity, remainingQuantity);
            throw new IllegalStateException("Insufficient liquidity in the partial order book to absorb the market order.");
        }

        return totalCost / targetQuantity;
    }

    /**
     * Obtains the absolute best top-of-book price for comparison without execution volumes.
     */
    public double getBestAvailablePrice(TradeSide side) {
        List<PriceLevel> levels = side == TradeSide.LONG ? bestAsks : bestBids;
        if (levels == null || levels.isEmpty()) return 0.0;
        return levels.get(0).price();
    }
}
