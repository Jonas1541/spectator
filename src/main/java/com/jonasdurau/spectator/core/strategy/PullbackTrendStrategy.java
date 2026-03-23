package com.jonasdurau.spectator.core.strategy;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.domain.MarketRegime;
import com.jonasdurau.spectator.core.domain.OrderFlowContext;
import com.jonasdurau.spectator.core.domain.TradeSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.OpenPriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;

import org.ta4j.core.indicators.helpers.HighestValueIndicator;
import org.ta4j.core.indicators.helpers.LowestValueIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;

import java.util.List;

@Component
public class PullbackTrendStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(PullbackTrendStrategy.class);

    private static final int EMA_50 = 50;

    public PullbackTrendStrategy() {}

    @Override
    public String getName() {
        return "1H 50-EMA Pullback Engine";
    }

    @Override
    public TradeSignal evaluate(List<Candle> recent1hCandles, MarketRegime current4hRegime, double currentPrice, OrderFlowContext orderFlowContext) {
        if (current4hRegime != MarketRegime.TRENDING_UP && current4hRegime != MarketRegime.TRENDING_DOWN) {
            return TradeSignal.ignore();
        }

        if (recent1hCandles.size() <= EMA_50) {
            return TradeSignal.ignore();
        }

        BarSeries series = Ta4jMapper.toBarSeries(recent1hCandles, "Bitcoin_1H");
        int endIndex = series.getEndIndex();

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        OpenPriceIndicator openPrice = new OpenPriceIndicator(series);
        EMAIndicator ema50 = new EMAIndicator(closePrice, EMA_50);
        
        // NOVO: Filtro de Volume Institucional
        VolumeIndicator volume = new VolumeIndicator(series);
        SMAIndicator volumeSma = new SMAIndicator(volume, 20); // Média de volume das últimas 20 horas
        
        // NOVO: ATR para o Stop Loss Dinâmico
        ATRIndicator atr = new ATRIndicator(series, 14);

        // NOVO: MACD para confirmar o Momento (Momentum)
        MACDIndicator macd = new MACDIndicator(closePrice, 12, 26);
        EMAIndicator macdSignalLine = new EMAIndicator(macd, 9);
        
        double currentMacd = macd.getValue(endIndex).doubleValue();
        double currentSignal = macdSignalLine.getValue(endIndex).doubleValue();
        
        // MACD Bullish = Linha MACD > Linha de Sinal
        boolean isMacdBullish = currentMacd > currentSignal;
        boolean isMacdBearish = currentMacd < currentSignal;
        
        double cPrice = closePrice.getValue(endIndex).doubleValue();
        double oPrice = openPrice.getValue(endIndex).doubleValue();
        double e50 = ema50.getValue(endIndex).doubleValue();
        double currentVolume = volume.getValue(endIndex).doubleValue();
        double avgVolume = volumeSma.getValue(endIndex).doubleValue();
        double currentAtr = atr.getValue(endIndex).doubleValue();
        
        // Structural Targets Pivot Calculation (20 Candles Swing High/Lows)
        HighPriceIndicator highPrice = new HighPriceIndicator(series);
        LowPriceIndicator lowPrice = new LowPriceIndicator(series);
        HighestValueIndicator highestHigh20 = new HighestValueIndicator(highPrice, 20);
        LowestValueIndicator lowestLow20 = new LowestValueIndicator(lowPrice, 20);
        double recentHigh = highestHigh20.getValue(endIndex).doubleValue();
        double recentLow = lowestLow20.getValue(endIndex).doubleValue();

        double currentLow = lowPrice.getValue(endIndex).doubleValue();
        double currentHigh = highPrice.getValue(endIndex).doubleValue();
        
        // Regra de Ouro: Só operamos se o volume atual for maior que a média!
        boolean strongVolume = currentVolume > avgVolume;

        if (current4hRegime == MarketRegime.TRENDING_UP) {
            // === LONG: Pin Bar na EMA 50 (Vela Única) ===
            // A mínima tocou/caiu ABAIXO da EMA 50, mas o close recuperou ACIMA dela, numa vela de alta
            boolean longPinBar = currentLow < e50 && cPrice > e50 && cPrice > oPrice;

            if (longPinBar && strongVolume && isMacdBullish) { 
                if (orderFlowContext != null && orderFlowContext.cumulativeVolumeDelta() < 0) {
                    log.info("[{}] Trigger ignored! Order Flow is heavily bearish (CVD: {}).", getName(), orderFlowContext.cumulativeVolumeDelta());
                    return TradeSignal.ignore();
                }
                if (orderFlowContext != null && orderFlowContext.currentFundingRate() > 0.0005) {
                    log.info("[{}] Trigger ignored! Retail is over-leveraged Long (Funding: {}).", getName(), orderFlowContext.currentFundingRate());
                    return TradeSignal.ignore();
                }
                
                log.info("[{}] PIN BAR LONG! Low ({}) swept below EMA-50 ({}), close ({}) recovered above. MACD Bullish.", 
                         getName(), String.format("%.2f", currentLow), String.format("%.2f", e50), String.format("%.2f", cPrice));
                
                // Stop Loss Dinâmico 3.0x o ATR abaixo da entrada
                double stopLoss = cPrice - (currentAtr * 3.0);
                
                // Phase 13.4: Structural Take Profit on recent Swing High
                double target = recentHigh;
                double risk = cPrice - stopLoss;
                double reward = target - cPrice;
                
                if (risk <= 0 || (reward / risk) < 1.2) {
                    log.info("[{}] Trigger ignored! Structural target {} offers poor R:R ({}).", getName(), target, String.format("%.2f", reward / risk));
                    return TradeSignal.ignore();
                }

                // Phase 16: Partial Take Profit at midpoint (50% of lot)
                double tp1 = cPrice + (risk * 1.5); // TP1 at 1.5R
                return TradeSignal.enterWithPartialTp(TradeSide.LONG, stopLoss, target, 2.0, null, 0.30, tp1, 0.5);
            }
        } else if (current4hRegime == MarketRegime.TRENDING_DOWN) {
            // === SHORT: Pin Bar na EMA 50 (Vela Única) ===
            // A máxima tocou/subiu ACIMA da EMA 50, mas o close retraiu ABAIXO dela, numa vela de baixa
            boolean shortPinBar = currentHigh > e50 && cPrice < e50 && cPrice < oPrice;
            
            if (shortPinBar && strongVolume && isMacdBearish) { 
                if (orderFlowContext != null && orderFlowContext.cumulativeVolumeDelta() > 0) {
                    log.info("[{}] Trigger ignored! Order Flow is heavily bullish (CVD: {}).", getName(), orderFlowContext.cumulativeVolumeDelta());
                    return TradeSignal.ignore();
                }
                if (orderFlowContext != null && orderFlowContext.currentFundingRate() < -0.0005) {
                    log.info("[{}] Trigger ignored! Retail is over-leveraged Short (Funding: {}).", getName(), orderFlowContext.currentFundingRate());
                    return TradeSignal.ignore();
                }
                
                log.info("[{}] PIN BAR SHORT! High ({}) swept above EMA-50 ({}), close ({}) recovered below. MACD Bearish.", 
                         getName(), String.format("%.2f", currentHigh), String.format("%.2f", e50), String.format("%.2f", cPrice));
                
                // Stop Loss Dinâmico 1.5x o ATR acima da entrada
                double stopLoss = cPrice + (currentAtr * 1.5);
                
                // Phase 13.4: Structural Take Profit on recent Swing Low
                double target = recentLow;
                double risk = stopLoss - cPrice;
                double reward = cPrice - target;
                
                if (risk <= 0 || (reward / risk) < 1.2) {
                    log.info("[{}] Trigger ignored! Structural target {} offers poor R:R ({}).", getName(), target, String.format("%.2f", reward / risk));
                    return TradeSignal.ignore();
                }
                
                // Phase 16: Partial Take Profit at midpoint (50% of lot)
                double tp1 = cPrice - (risk * 1.5); // TP1 at 1.5R
                return TradeSignal.enterWithPartialTp(TradeSide.SHORT, stopLoss, target, 2.0, null, 0.30, tp1, 0.5);
            }
        }

        return TradeSignal.ignore();
    }
}
