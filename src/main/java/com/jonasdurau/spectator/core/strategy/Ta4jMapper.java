package com.jonasdurau.spectator.core.strategy;

import com.jonasdurau.spectator.core.domain.Candle;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.num.DecimalNum;

import java.time.Duration;
import java.util.List;

public class Ta4jMapper {

    /**
     * Converte nossa lista de Candles do banco para uma BarSeries do ta4j.
     */
    public static BarSeries toBarSeries(List<Candle> candles, String seriesName) {
        BarSeries series = new BaseBarSeriesBuilder()
                .withName(seriesName)
                .build();

        for (Candle c : candles) {
            
            // Cria o Bar usando a precisão decimal nativa do ta4j
            Bar bar = series.barBuilder()
                .timePeriod(Duration.ofHours(1))
                .endTime(c.getTime())
                .openPrice(DecimalNum.valueOf(c.getOpen()))
                .highPrice(DecimalNum.valueOf(c.getHigh()))
                .lowPrice(DecimalNum.valueOf(c.getLow()))
                .closePrice(DecimalNum.valueOf(c.getClose()))
                .volume(DecimalNum.valueOf(c.getVolume()))
                .build();

            series.addBar(bar);
        }

        return series;
    }
}