package com.jonasdurau.spectator.core.strategy;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.domain.MarketRegime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import smile.sequence.HMM;
import java.util.List;

@Service
public class RegimeAnalyzerService {

    private static final Logger log = LoggerFactory.getLogger(RegimeAnalyzerService.class);

    private static final int HMM_WINDOW_CANDLES = 250;

    /**
     * Estimates the latent market regime by fitting a Gaussian HMM on Log-Returns.
     */
    public MarketRegime analyze(List<Candle> recentCandles) {
        if (recentCandles.size() < HMM_WINDOW_CANDLES) {
            return MarketRegime.SIDEWAYS; 
        }

        // 1. Extract the rolling window
        List<Candle> window = recentCandles.subList(recentCandles.size() - HMM_WINDOW_CANDLES, recentCandles.size());
        
        // 2. Compute Discretized Observations (Log Returns as 5 Bins)
        // Bin 0: Strong Down (< -1%)
        // Bin 1: Weak Down (< -0.1%)
        // Bin 2: Flat
        // Bin 3: Weak Up (> +0.1%)
        // Bin 4: Strong Up (> +1%)
        int[] intObservations = new int[window.size() - 1];
        double[] observations = new double[window.size() - 1];
        
        for (int i = 1; i < window.size(); i++) {
            double c = window.get(i).getClose();
            double prevC = window.get(i - 1).getClose();
            double r = Math.log(c / prevC);
            observations[i - 1] = r;
            
            if (r <= -0.01) intObservations[i - 1] = 0;
            else if (r < -0.001) intObservations[i - 1] = 1;
            else if (r <= 0.001) intObservations[i - 1] = 2;
            else if (r < 0.01) intObservations[i - 1] = 3;
            else intObservations[i - 1] = 4;
        }

        // 3. Supervised HMM Training (Smile 5.2.1 requires sequence and label matrices)
        int[] labels = new int[intObservations.length];
        for(int i = 0; i < labels.length; i++) {
            // Seed labels via a basic Momentum heuristic 
            double r = observations[i];
            if (r > 0.005) labels[i] = 1;      // Trend Up
            else if (r < -0.005) labels[i] = 2; // Trend Down
            else labels[i] = 0;                // Sideways
        }

        HMM hmm = null;
        try {
            int[][] seqMatrix = { intObservations };
            int[][] labMatrix = { labels };
            hmm = HMM.fit(seqMatrix, labMatrix);
        } catch (Exception e) {
            log.error("HMM Fit error. Fallback to SIDEWAYS", e);
            return MarketRegime.SIDEWAYS;
        }

        // 4. Viterbi Decoding of the highest probability Latent State
        int currentStateIdx = 0;
        if (hmm != null) {
            int[] viterbiPath = hmm.predict(intObservations);
            currentStateIdx = viterbiPath[viterbiPath.length - 1];
        }

        // 5. Map the mathematical State to our Domain Regime
        switch(currentStateIdx) {
            case 1:
                return MarketRegime.TRENDING_UP;
            case 2:
                return MarketRegime.TRENDING_DOWN;
            case 0:
            default:
                return MarketRegime.SIDEWAYS;
        }
    }
}