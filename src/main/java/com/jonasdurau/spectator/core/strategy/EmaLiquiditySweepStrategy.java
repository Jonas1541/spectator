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
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.OpenPriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;

import java.util.List;

@Component
public class EmaLiquiditySweepStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(EmaLiquiditySweepStrategy.class);

    private static final int EMA_50 = 50;

    public EmaLiquiditySweepStrategy() {
    }

    @Override
    public String getName() {
        return "1H 50-EMA Liquidity Sweep";
    }

    @Override
    public TradeSignal evaluate(List<Candle> recent1hCandles, MarketRegime current4hRegime, double currentPrice,
            OrderFlowContext orderFlowContext) {
        if (current4hRegime != MarketRegime.TRENDING_UP && current4hRegime != MarketRegime.TRENDING_DOWN) {
            return TradeSignal.ignore();
        }

        if (recent1hCandles.size() <= EMA_50) {
            return TradeSignal.ignore();
        }

        BarSeries series = Ta4jMapper.toBarSeries(recent1hCandles, "Crypto_1H");
        int endIndex = series.getEndIndex();

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        OpenPriceIndicator openPrice = new OpenPriceIndicator(series);
        EMAIndicator ema50 = new EMAIndicator(closePrice, EMA_50);
        HighPriceIndicator highPrice = new HighPriceIndicator(series);
        LowPriceIndicator lowPrice = new LowPriceIndicator(series);
        ATRIndicator atr = new ATRIndicator(series, 14);

        double cPrice = closePrice.getValue(endIndex).doubleValue();
        double oPrice = openPrice.getValue(endIndex).doubleValue();
        double e50 = ema50.getValue(endIndex).doubleValue();
        double currentLow = lowPrice.getValue(endIndex).doubleValue();
        double currentHigh = highPrice.getValue(endIndex).doubleValue();
        double currentAtr = atr.getValue(endIndex).doubleValue();

        if (current4hRegime == MarketRegime.TRENDING_UP) {
            // === LONG: Pin Bar na EMA 50 (Vela Única) ===
            // A mínima tocou/caiu ABAIXO da EMA 50, mas o close recuperou ACIMA dela, numa vela de alta
            boolean longPinBar = currentLow < e50 && cPrice > e50 && cPrice > oPrice;

            if (longPinBar) {
                if (orderFlowContext != null && orderFlowContext.cumulativeVolumeDelta() < 0) {
                    log.info("[{}] Trigger ignored! Order Flow is heavily bearish (CVD: {}).", getName(),
                            orderFlowContext.cumulativeVolumeDelta());
                    return TradeSignal.ignore();
                }
                if (orderFlowContext != null && orderFlowContext.currentFundingRate() > 0.0005) {
                    log.info("[{}] Trigger ignored! Retail is over-leveraged Long (Funding: {}).", getName(),
                            orderFlowContext.currentFundingRate());
                    return TradeSignal.ignore();
                }

                // Stop Loss no fundo exato do Pin Bar (pavio)
                double stopLoss = lowPrice.getValue(endIndex).doubleValue();
                double risk = cPrice - stopLoss;
                if (risk <= 0)
                    return TradeSignal.ignore();

                // 1. Removemos o teto de lucro (Alvo inatingível para forçar a saída pelo Trailing)
                double takeProfit = cPrice * 10.0;

                // 2. Calculamos o Trailing Stop dinâmico: 2x a volatilidade atual (ATR) da moeda
                double trailingMultiplier = (currentAtr * 2.0) / risk;
                
                log.info("[{}] PIN BAR LONG (ATR Trailing)! SL: {}, ATR: {}", getName(), String.format("%.2f", stopLoss), String.format("%.2f", currentAtr));

                return TradeSignal.enter(TradeSide.LONG, stopLoss, takeProfit, null, trailingMultiplier, null);
            }
        } else if (current4hRegime == MarketRegime.TRENDING_DOWN) {
            // === SHORT: Pin Bar na EMA 50 (Vela Única) ===
            // A máxima tocou/subiu ACIMA da EMA 50, mas o close retraiu ABAIXO dela, numa vela de baixa
            boolean shortPinBar = currentHigh > e50 && cPrice < e50 && cPrice < oPrice;

            if (shortPinBar) {
                if (orderFlowContext != null && orderFlowContext.cumulativeVolumeDelta() > 0) {
                    log.info("[{}] Trigger ignored! Order Flow is heavily bullish (CVD: {}).", getName(),
                            orderFlowContext.cumulativeVolumeDelta());
                    return TradeSignal.ignore();
                }
                if (orderFlowContext != null && orderFlowContext.currentFundingRate() < -0.0005) {
                    log.info("[{}] Trigger ignored! Retail is over-leveraged Short (Funding: {}).", getName(),
                            orderFlowContext.currentFundingRate());
                    return TradeSignal.ignore();
                }

                // Stop Loss no topo exato do Pin Bar (pavio)
                double stopLoss = highPrice.getValue(endIndex).doubleValue();
                double risk = stopLoss - cPrice;
                if (risk <= 0)
                    return TradeSignal.ignore();

                // Alvo no chão (zero virtual) para forçar o Trailing Stop
                double takeProfit = 0.0001;

                // Calculamos o Trailing Stop dinâmico: 2x a volatilidade atual (ATR) da moeda
                double trailingMultiplier = (currentAtr * 2.0) / risk;
                
                log.info("[{}] PIN BAR SHORT (ATR Trailing)! SL: {}, ATR: {}", getName(), String.format("%.2f", stopLoss), String.format("%.2f", currentAtr));

                return TradeSignal.enter(TradeSide.SHORT, stopLoss, takeProfit, null, trailingMultiplier, null);
            }
        }

        return TradeSignal.ignore();
    }
}