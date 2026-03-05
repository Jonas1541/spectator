package com.jonasdurau.spectator.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RiskManagerService {

    private static final Logger log = LoggerFactory.getLogger(RiskManagerService.class);

    // Hardcoded for now until we have a proper Portfolio module
    private static final double MOCK_ACCOUNT_EQUITY = 10000.0;
    
    // As per the manual: Risk per trade is 0.5% to 1.0%. Let's default to 1.0%
    private static final double DEFAULT_RISK_PERCENTAGE = 0.01; 

    /**
     * Calculates the position size based on the Stop Loss distance.
     * Position Size = (Account Equity * Risk Percentage) / (Entry Price - Stop Loss Price)
     *
     * @param currentPrice The entry price for the trade
     * @param stopLossPrice The price at which the stop loss is triggered
     * @return The calculated position size (quantity)
     */
    public double calculatePositionSize(double currentPrice, double stopLossPrice) {
        return calculatePositionSize(currentPrice, stopLossPrice, MOCK_ACCOUNT_EQUITY, DEFAULT_RISK_PERCENTAGE);
    }
    
    public double calculatePositionSize(double currentPrice, double stopLossPrice, double accountEquity, double riskPercentage) {
        double maxLossFait = accountEquity * riskPercentage;
        double stopDistance = Math.abs(currentPrice - stopLossPrice);
        
        if (stopDistance <= 0) {
            log.warn("Invalid stop distance. StopLoss {} is equal to or same as Entry {}. Defaulting to minimum.", stopLossPrice, currentPrice);
            return 0.001; // Minimum fallback
        }

        double positionSize = maxLossFait / stopDistance;
        log.info("Risk Manager: Equity=${}, Risk={}, SL Dist=${}. Computed Position Size={}", 
                accountEquity, riskPercentage, stopDistance, positionSize);
                
        return positionSize;
    }
}
