package com.jonasdurau.spectator.ui.components;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jonasdurau.spectator.core.domain.Candle;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.html.Div;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TradingViewChart extends Div {

    // Instanciamos o serializador JSON padrão do Spring
    private static final ObjectMapper mapper = new ObjectMapper();

    public TradingViewChart() {
        setWidthFull();
        setHeight("600px");
        // O container precisa de position relative para hospedar o LightweightCharts
        // perfeitamente
        getStyle().set("position", "relative");
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);

        getElement().executeJs(
                """
                            const container = $0;

                            const renderChart = () => {
                                try {
                                    if (container.chart) return;

                                    const chart = window.LightweightCharts.createChart(container, {
                                        autoSize: true,
                                        layout: { textColor: '#d1d4dc', background: { type: 'solid', color: '#131722' } },
                                        grid: { vertLines: { color: '#2B2B43' }, horzLines: { color: '#2B2B43' } },
                                        crosshair: { mode: window.LightweightCharts.CrosshairMode.Normal },
                                        timeScale: { timeVisible: true, secondsVisible: false }
                                    });

                                    const series = chart.addCandlestickSeries({
                                        upColor: '#26a69a', downColor: '#ef5350', borderVisible: false,
                                        wickUpColor: '#26a69a', wickDownColor: '#ef5350'
                                    });

                                    container.chart = chart;
                                    container.candlestickSeries = series;

                                    if (container._pendingData) {
                                        try {
                                            series.setData(container._pendingData);
                                            if (container._pendingData.length > 0) {
                                                chart.timeScale().fitContent();
                                            }
                                        } catch(e) {
                                            console.error("TradingView pending data error:", e, container._pendingData);
                                        }
                                        container._pendingData = null;
                                    }
                                } catch(e) {
                                    console.error("TradingView renderChart Error:", e);
                                }
                            };

                            const loadLibrary = () => {
                                if (window.LightweightCharts) {
                                    renderChart();
                                    return;
                                }

                                if (window._lightweightChartsLoading) {
                                    const check = setInterval(() => {
                                        if (window.LightweightCharts) {
                                            clearInterval(check);
                                            renderChart();
                                        }
                                    }, 100);
                                    return;
                                }

                                window._lightweightChartsLoading = true;
                                const script = document.createElement('script');
                                script.src = 'https://unpkg.com/lightweight-charts@4.1.1/dist/lightweight-charts.standalone.production.js';
                                script.onload = renderChart;
                                script.onerror = (err) => console.error("Error loading LightweightCharts script", err);
                                document.head.appendChild(script);
                            };

                            loadLibrary();
                        """,
                getElement());
    }

    public void setHistoricalData(List<Candle> candles) {
        if (candles == null || candles.isEmpty())
            return;

        // A GRANDE CORREÇÃO: Agrupar pelo mesmo número exato que o TradingView vai ler
        List<Map<String, Object>> safeData = candles.stream()
                .filter(c -> c.getTime() != null)
                .collect(Collectors.toMap(
                        c -> c.getTime().getEpochSecond(), // Chave é o segundo exato
                        c -> c,
                        (existing, replacement) -> existing // Se houver colisão no mesmo segundo, descarta o clone
                ))
                .values().stream()
                .sorted(Comparator.comparing(Candle::getTime))
                .map(this::candleToMap)
                .toList();

        try {
            String jsonStr = mapper.writeValueAsString(safeData);

            getElement().executeJs("""
                        const container = $0;
                        const data = JSON.parse($1);

                        if (container.candlestickSeries) {
                            try {
                                container.candlestickSeries.setData(data);
                                if (data.length > 0) {
                                    container.chart.timeScale().fitContent();
                                }
                            } catch(e) {
                                console.error("TradingView Data Error:", e, data);
                            }
                        } else {
                            container._pendingData = data;
                        }
                    """, getElement(), jsonStr);

        } catch (JsonProcessingException e) {
            System.err.println("Erro ao serializar dados do grafico: " + e.getMessage());
        }
    }

    public void updateLiveTick(Candle candle) {
        if (candle == null || candle.getTime() == null)
            return;

        try {
            String jsonStr = mapper.writeValueAsString(candleToMap(candle));

            getElement().executeJs("""
                        const container = $0;
                        const tick = JSON.parse($1);

                        if (container.candlestickSeries) {
                            try {
                                container.candlestickSeries.update(tick);
                            } catch(e) {
                                console.warn("TradingView Tick Update Error:", e);
                            }
                        } else {
                            if (!container._pendingData) container._pendingData = [];
                            const existing = container._pendingData.findIndex(d => d.time === tick.time);
                            if (existing >= 0) {
                                container._pendingData[existing] = tick;
                            } else {
                                container._pendingData.push(tick);
                            }
                            container._pendingData.sort((a, b) => a.time - b.time);
                        }
                    """, getElement(), jsonStr);

        } catch (JsonProcessingException e) {
            System.err.println("Erro ao serializar tick: " + e.getMessage());
        }
    }

    private Map<String, Object> candleToMap(Candle c) {
        return Map.of(
                "time", c.getTime().getEpochSecond(),
                "open", c.getOpen(),
                "high", c.getHigh(),
                "low", c.getLow(),
                "close", c.getClose());
    }
}