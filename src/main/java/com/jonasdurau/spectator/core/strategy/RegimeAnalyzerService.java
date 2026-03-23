package com.jonasdurau.spectator.core.strategy;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.domain.MarketRegime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import smile.sequence.HMM;

import java.util.List;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;

@Service
public class RegimeAnalyzerService {

    private static final Logger log = LoggerFactory.getLogger(RegimeAnalyzerService.class);

    private static final int HMM_WINDOW_CANDLES = 250;
    
    // Phase 17: 2D Composite Grid — 5 return bins × 3 volume bins = 15 joint states
    private static final int RETURN_BINS = 5;
    private static final int VOLUME_BINS = 3;
    private static final int TOTAL_SYMBOLS = RETURN_BINS * VOLUME_BINS; // 15

    /**
     * Estimates the latent market regime by fitting a Discrete HMM on 
     * 2D composite observations: [Log-Return Bin × Volume Bin].
     */
    public MarketRegime analyze(List<Candle> recentCandles) {
        if (recentCandles.size() < HMM_WINDOW_CANDLES) {
            return MarketRegime.SIDEWAYS; 
        }

        // 1. Extract the rolling window
        List<Candle> window = recentCandles.subList(recentCandles.size() - HMM_WINDOW_CANDLES, recentCandles.size());
        
        // 2. Build TA4J series for ATR and Volume indicators
        BarSeries series = Ta4jMapper.toBarSeries(window, "HMM_Window");
        ATRIndicator atr = new ATRIndicator(series, 14);
        VolumeIndicator volume = new VolumeIndicator(series);
        SMAIndicator volumeSma = new SMAIndicator(volume, 20);
        
        int len = window.size() - 1;
        int[] compositeObservations = new int[len];
        double[] rawReturns = new double[len];
        double[] atrPercents = new double[len];
        
        for (int i = 1; i < window.size(); i++) {
            double c = window.get(i).getClose();
            double prevC = window.get(i - 1).getClose();
            double r = Math.log(c / prevC);
            rawReturns[i - 1] = r;
            
            // --- Dimension 1: Return Bin (ATR-scaled, 5 bins) ---
            double currentAtr = atr.getValue(i).doubleValue();
            double atrPercent = currentAtr / c;
            atrPercents[i - 1] = atrPercent;
            
            double strongBand = atrPercent * 1.5;
            double weakBand = atrPercent * 0.5;
            
            int returnBin;
            if (r <= -strongBand) returnBin = 0;       // Strong Down
            else if (r < -weakBand) returnBin = 1;     // Weak Down
            else if (r <= weakBand) returnBin = 2;      // Flat
            else if (r < strongBand) returnBin = 3;     // Weak Up
            else returnBin = 4;                         // Strong Up
            
            // --- Dimension 2: Volume Bin (Relative to 20-SMA, 3 bins) ---
            double currentVol = volume.getValue(i).doubleValue();
            double avgVol = volumeSma.getValue(i).doubleValue();
            double volRatio = avgVol > 0 ? currentVol / avgVol : 1.0;
            
            int volumeBin;
            if (volRatio < 0.7) volumeBin = 0;          // Low Volume (Quiet)
            else if (volRatio <= 1.5) volumeBin = 1;    // Normal Volume
            else volumeBin = 2;                          // High Volume (Conviction)
            
            // --- Composite: returnBin * VOLUME_BINS + volumeBin ---
            compositeObservations[i - 1] = returnBin * VOLUME_BINS + volumeBin;
        }

        // 3. Supervised HMM Training with composite symbols
        int[] labels = new int[len];
        for (int i = 0; i < labels.length; i++) {
            double r = rawReturns[i];
            double atrPct = atrPercents[i];
            
            if (r > atrPct * 1.0) labels[i] = 1;       // Trend Up
            else if (r < -atrPct * 1.0) labels[i] = 2;  // Trend Down
            else labels[i] = 0;                           // Sideways
        }

        HMM hmm = null;
        try {
            int[][] seqMatrix = { compositeObservations };
            int[][] labMatrix = { labels };
            hmm = HMM.fit(seqMatrix, labMatrix);
        } catch (Exception e) {
            log.error("HMM Fit error. Fallback to SIDEWAYS", e);
            return MarketRegime.SIDEWAYS;
        }

        // 4. Viterbi Decoding of the highest probability Latent State
        int currentStateIdx = 0;
        if (hmm != null) {
            int[] viterbiPath = hmm.predict(compositeObservations);
            currentStateIdx = viterbiPath[viterbiPath.length - 1];
        }

        // 5. Map the mathematical State to our Domain Regime
        switch (currentStateIdx) {
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