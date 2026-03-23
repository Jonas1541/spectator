package com.jonasdurau.spectator.core.service;

import com.jonasdurau.spectator.core.domain.OrderFlowContext;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;

@Service
public class OrderFlowService {
    
    private final Map<String, DoubleAdder> cvdBySymbol = new ConcurrentHashMap<>();
    private final Map<String, Double> fundingBySymbol = new ConcurrentHashMap<>();
    
    public void registerTrade(String symbol, double quantity, boolean isBuyerMaker) {
        DoubleAdder cvd = cvdBySymbol.computeIfAbsent(symbol, k -> new DoubleAdder());
        if (isBuyerMaker) {
            cvd.add(-quantity);
        } else {
            cvd.add(quantity);
        }
    }
    
    public void updateFundingRate(String symbol, double fundingRate) {
        fundingBySymbol.put(symbol, fundingRate);
    }
    
    public OrderFlowContext getSnapshot(String symbol) {
        DoubleAdder cvd = cvdBySymbol.get(symbol);
        double cvdValue = cvd != null ? cvd.sum() : 0.0;
        double funding = fundingBySymbol.getOrDefault(symbol, 0.0);
        return new OrderFlowContext(cvdValue, funding);
    }
    
    public void resetCvdForNewSession(String symbol) {
        DoubleAdder cvd = cvdBySymbol.get(symbol);
        if (cvd != null) cvd.reset();
    }
}

