package com.jonasdurau.spectator.core.strategy;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.domain.MarketRegime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.util.List;

@Service
public class RegimeAnalyzerService {

    private static final Logger log = LoggerFactory.getLogger(RegimeAnalyzerService.class);

    // Parâmetros clássicos de Análise Técnica
    private static final int EMA_PERIOD = 200;
    private static final int ADX_PERIOD = 14;
    private static final int ATR_PERIOD = 14;
    
    // Limiares (Thresholds) da sua estratégia
    private static final double ADX_TREND_THRESHOLD = 20.0;
    private static final double ATR_VOLATILITY_MULTIPLIER = 1.5;

    /**
     * Analisa o mercado baseado nos candles recentes e retorna o estado atual.
     * @param recentCandles Lista ordenada do mais antigo para o mais novo.
     */
    public MarketRegime analyze(List<Candle> recentCandles) {
        if (recentCandles.size() <= EMA_PERIOD) {
            log.warn("Not enough candles to calculate EMA {}. Need at least {}, got {}", EMA_PERIOD, EMA_PERIOD + 1, recentCandles.size());
            return MarketRegime.SIDEWAYS; // Estado de segurança padrão
        }

        // 1. Converter para o formato ta4j
        BarSeries series = Ta4jMapper.toBarSeries(recentCandles, "Bitcoin_1H");
        int endIndex = series.getEndIndex();

        // 2. Preparar os Indicadores
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        EMAIndicator ema200 = new EMAIndicator(closePrice, EMA_PERIOD);
        ADXIndicator adx = new ADXIndicator(series, ADX_PERIOD);
        
        // Para volatilidade, comparamos o ATR atual com a média do ATR (SMA do ATR)
        ATRIndicator atr = new ATRIndicator(series, ATR_PERIOD);
        SMAIndicator averageAtr = new SMAIndicator(atr, ATR_PERIOD * 2);

        // 3. Extrair os valores do momento atual (último candle)
        double currentPrice = closePrice.getValue(endIndex).doubleValue();
        double currentEma = ema200.getValue(endIndex).doubleValue();
        double currentAdx = adx.getValue(endIndex).doubleValue();
        
        double currentAtr = atr.getValue(endIndex).doubleValue();
        double baselineAtr = averageAtr.getValue(endIndex).doubleValue();

        // 4. Aplicar as Regras de Negócio (Decisão)
        
        // Regra 1: O mercado está explodindo de volatilidade? (ATR muito acima da média histórica)
        if (currentAtr > (baselineAtr * ATR_VOLATILITY_MULTIPLIER)) {
            return MarketRegime.VOLATILE;
        }

        // Regra 2: O mercado está sem força/lateralizado?
        if (currentAdx < ADX_TREND_THRESHOLD) {
            return MarketRegime.SIDEWAYS;
        }

        // Regra 3: Se tem força (ADX >= 20), para onde é a tendência?
        if (currentPrice > currentEma) {
            return MarketRegime.TRENDING_UP;
        } else {
            return MarketRegime.TRENDING_DOWN;
        }
    }
}