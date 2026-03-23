package com.jonasdurau.spectator.core.service;

import com.jonasdurau.spectator.core.domain.TradeSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OrderBookService {

    private static final Logger log = LoggerFactory.getLogger(OrderBookService.class);

    public record PriceLevel(double price, double quantity) {}

    private final Map<String, List<PriceLevel>> bidsBySymbol = new ConcurrentHashMap<>();
    private final Map<String, List<PriceLevel>> asksBySymbol = new ConcurrentHashMap<>();

    public void updateOrderBook(String symbol, List<PriceLevel> newBids, List<PriceLevel> newAsks) {
        this.bidsBySymbol.put(symbol, new ArrayList<>(newBids));
        this.asksBySymbol.put(symbol, new ArrayList<>(newAsks));
    }

    /**
     * Calculates the Volume Weighted Average Price (VWAP) if we send a Market Order of the given quantity.
     */
    public double calculateExpectedFillPrice(String symbol, TradeSide side, double targetQuantity) throws IllegalStateException {
        List<PriceLevel> levels = side == TradeSide.LONG ? asksBySymbol.get(symbol) : bidsBySymbol.get(symbol);

        if (levels == null || levels.isEmpty()) {
            throw new IllegalStateException("Order Book memory is empty for " + symbol + ". Awaiting WebSocket stream...");
        }

        double remainingQuantity = targetQuantity;
        double totalCost = 0.0;
        
        for (PriceLevel level : levels) {
            double fillQty = Math.min(level.quantity(), remainingQuantity);
            totalCost += (level.price() * fillQty);
            remainingQuantity -= fillQty;

            if (remainingQuantity <= 0) {
                break;
            }
        }

        if (remainingQuantity > 0) {
            log.warn("Not enough volume in top 5 levels to fill {} units for {}. Remaining {}", targetQuantity, symbol, remainingQuantity);
            throw new IllegalStateException("Insufficient liquidity in the partial order book to absorb the market order.");
        }

        return totalCost / targetQuantity;
    }

    /**
     * Obtains the absolute best top-of-book price for a specific symbol.
     */
    public double getBestAvailablePrice(String symbol, TradeSide side) {
        List<PriceLevel> levels = side == TradeSide.LONG ? asksBySymbol.get(symbol) : bidsBySymbol.get(symbol);
        if (levels == null || levels.isEmpty()) return 0.0;
        return levels.get(0).price();
    }
}

