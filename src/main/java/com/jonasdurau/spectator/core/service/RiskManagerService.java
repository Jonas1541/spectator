package com.jonasdurau.spectator.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RiskManagerService {

    private static final Logger log = LoggerFactory.getLogger(RiskManagerService.class);
    
    private static final double KELLY_FRACTION_MULTIPLIER = 0.5; // Half-Kelly (optimizes growth while limiting capital volatility)
    private static final double MAX_EXPOSURE_PERCENTAGE = 1.0; // 100% Max Global Exposure (1x leverage) to prevent liquidation risk
    private static final double MAX_SINGLE_TRADE_RISK = 0.02; // 2% absolute risk cap per trade to absorb flash crashes

    /**
     * Calculates the position size based on the Fractional Kelly Criterion.
     * 
     * f* = p - (q / b)
     * Where p = win probability, q = loss probability, b = reward/risk ratio
     */
    public double calculateKellyPositionSize(
            double currentPrice, 
            double stopLossPrice, 
            double targetPrice, 
            double winProbability, 
            double accountEquity, 
            double currentExposurePercentage) {
        
        // 1. Calculate Reward/Risk Ratio (b)
        double riskDistance = Math.abs(currentPrice - stopLossPrice);
        double rewardDistance = Math.abs(targetPrice - currentPrice);
        
        if (riskDistance <= 0 || rewardDistance <= 0) {
            log.warn("Invalid risk or reward distance. SL: {}, TP: {}, Entry: {}", stopLossPrice, targetPrice, currentPrice);
            return 0.0;
        }
        
        double rewardRisk = rewardDistance / riskDistance;
        
        // 2. Full Kelly Fraction (f*)
        double loseProbability = 1.0 - winProbability;
        double fullKelly = winProbability - (loseProbability / rewardRisk);
        
        if (fullKelly <= 0) {
            log.warn("Negative Kelly Fraction ({}). Edge is not positive. Rejecting trade.", fullKelly);
            return 0.0; 
        }
        
        // 3. Fractional Kelly (Quarter-Kelly)
        double fractionalKelly = fullKelly * KELLY_FRACTION_MULTIPLIER;
        
        // 4. Cap Single Trade Risk
        double riskPercentage = Math.min(fractionalKelly, MAX_SINGLE_TRADE_RISK);
        
        // 5. Max Exposure Validator
        if (currentExposurePercentage >= MAX_EXPOSURE_PERCENTAGE) {
            log.warn("Global Max Exposure ({}%) reached. Current: {}. Rejecting trade.", (MAX_EXPOSURE_PERCENTAGE * 100), (currentExposurePercentage * 100));
            return 0.0;
        }
        
        // Allowable space before hitting the 30% ceiling
        double allowableRisk = MAX_EXPOSURE_PERCENTAGE - currentExposurePercentage;
        double finalRiskPercentage = Math.min(riskPercentage, allowableRisk);
        
        // 6. Calculate Position Size Quantity
        double maxLossFiat = accountEquity * finalRiskPercentage;
        double positionSize = maxLossFiat / riskDistance;
        
        log.info("Kelly Sizing: WinRate={}, R:R={}, KellyFraction={}, PctRisk={}, FiatRisk=${}, PosSize={}", 
                winProbability, rewardRisk, fractionalKelly, finalRiskPercentage, maxLossFiat, positionSize);
                
        return positionSize;
    }
}
