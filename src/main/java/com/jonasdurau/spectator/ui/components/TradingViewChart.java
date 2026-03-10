package com.jonasdurau.spectator.ui.components;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jonasdurau.spectator.core.backtest.BacktestTrade;
import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.domain.RegimeChangeEvent;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.html.Div;

import java.time.Instant;
import java.util.ArrayList;
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

                                    // NEW v5 Unified Series API
                                    const series = chart.addSeries(window.LightweightCharts.CandlestickSeries, {
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
                                // Upgrade to v5
                                script.src = 'https://unpkg.com/lightweight-charts@5.1.0/dist/lightweight-charts.standalone.production.js';
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

    public void setBacktestData(List<Candle> candles, List<BacktestTrade> trades, List<RegimeChangeEvent> regimeChanges) {
        if (candles == null || candles.isEmpty()) return;

        // Higieniza as velas
        List<Map<String, Object>> safeData = candles.stream()
                .filter(c -> c.getTime() != null)
                .collect(Collectors.toMap(
                        c -> c.getTime().getEpochSecond(),
                        c -> c,
                        (existing, replacement) -> existing
                ))
                .values().stream()
                .sorted(Comparator.comparing(Candle::getTime))
                .map(this::candleToMap)
                .toList();

        // Mapeia os eventos para o formato do TradingView
        List<Map<String, Object>> markers = (trades == null) ? new ArrayList<>() : trades.stream().map(t -> {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("time", t.time().getEpochSecond());
            
            if (t.isEntry()) {
                if (t.side() == com.jonasdurau.spectator.core.domain.TradeSide.LONG) {
                    m.put("position", "belowBar");
                    m.put("color", "#26a69a"); // Verde
                    m.put("shape", "arrowUp");
                    m.put("text", "Buy");
                } else {
                    m.put("position", "aboveBar");
                    m.put("color", "#ef5350"); // Vermelho
                    m.put("shape", "arrowDown");
                    m.put("text", "Sell");
                }
            } else {
                String pnlText = String.format(java.util.Locale.US, "%.2f", t.pnl());
                String label = (t.pnl() >= 0 ? "TP (+" + pnlText + ")" : "SL (" + pnlText + ")");
                String color = t.pnl() >= 0 ? "#f5cb5c" : "#787878"; // Amarelo (Lucro), Cinza (Prejuízo)
                
                if (t.side() == com.jonasdurau.spectator.core.domain.TradeSide.LONG) {
                    m.put("position", "aboveBar"); 
                    m.put("shape", "arrowDown");
                } else {
                    m.put("position", "belowBar");
                    m.put("shape", "arrowUp");
                }
                m.put("color", color);
                m.put("text", label);
            }
            return m;
        }).collect(Collectors.toCollection(ArrayList::new));

        //Mapeia as mudanças de regime como "Bolinhas Azuis" acima do gráfico
        if (regimeChanges != null) {
            markers.addAll(regimeChanges.stream().map(r -> {
                Map<String, Object> m = new java.util.HashMap<>();
                m.put("time", r.time().getEpochSecond());
                m.put("position", "aboveBar");
                m.put("color", "#3b82f6"); // Azul vivo
                m.put("shape", "circle");
                m.put("text", "Regime: " + r.regime().name());
                return m;
            }).toList());
        }

        try {
            String candlesJson = mapper.writeValueAsString(safeData);
            String markersJson = mapper.writeValueAsString(markers);

            getElement().executeJs("""
                const container = $0;
                const candleData = JSON.parse($1);
                const rawMarkerData = JSON.parse($2);

                const applyData = () => {
                    if (container.candlestickSeries) {
                        try {
                            container.candlestickSeries.setData(candleData);
                            
                            if (rawMarkerData && rawMarkerData.length > 0) {
                                // 1. Ordena estritamente pelo tempo
                                rawMarkerData.sort((a, b) => a.time - b.time);
                                
                                // 2. Higienizador de Duplicatas (Mescla eventos no mesmo candle)
                                const uniqueMarkers = [];
                                const markerMap = new Map();
                                
                                rawMarkerData.forEach(m => {
                                    if (markerMap.has(m.time)) {
                                        const existing = markerMap.get(m.time);
                                        existing.text = existing.text + " & " + m.text; // Junta os textos
                                    } else {
                                        markerMap.set(m.time, m);
                                        uniqueMarkers.push(m);
                                    }
                                });
                                
                                // 3. A GRANDE MUDANÇA DA V5: O motor de plugins
                                if (container.markersPlugin) {
                                    container.markersPlugin.setMarkers(uniqueMarkers);
                                } else {
                                    container.markersPlugin = window.LightweightCharts.createSeriesMarkers(
                                        container.candlestickSeries, 
                                        uniqueMarkers
                                    );
                                }
                            }
                            
                            if (candleData.length > 0) {
                                container.chart.timeScale().fitContent();
                            }
                        } catch(e) {
                            console.error("TradingView Apply Error:", e);
                        }
                    } else {
                        setTimeout(applyData, 100);
                    }
                };
                applyData();
            """, getElement(), candlesJson, markersJson);

        } catch (JsonProcessingException e) {
            System.err.println("Erro ao serializar dados do backtest: " + e.getMessage());
        }
    }

    //MÉTODO PARA O DASHBOARD AO VIVO
    public void addLiveMarker(Instant time, String text, String color, String position, String shape) {
        Map<String, Object> marker = Map.of(
            "time", time.getEpochSecond(),
            "text", text,
            "color", color,
            "position", position,
            "shape", shape
        );
        try {
            String jsonStr = mapper.writeValueAsString(marker);
            getElement().executeJs("""
                const container = $0;
                const newMarker = JSON.parse($1);
                
                if (container.candlestickSeries) {
                    if (!container._rawMarkers) container._rawMarkers = [];
                    container._rawMarkers.push(newMarker);
                    container._rawMarkers.sort((a,b) => a.time - b.time);
                    
                    const uniqueMarkers = [];
                    const markerMap = new Map();
                    container._rawMarkers.forEach(raw => {
                        const m = {...raw};
                        if (markerMap.has(m.time)) {
                            const existing = markerMap.get(m.time);
                            if (!existing.text.includes(m.text)) existing.text = existing.text + " | " + m.text;
                        } else {
                            markerMap.set(m.time, m);
                            uniqueMarkers.push(m);
                        }
                    });
                    
                    if (container.markersPlugin) {
                        container.markersPlugin.setMarkers(uniqueMarkers);
                    } else {
                        container.markersPlugin = window.LightweightCharts.createSeriesMarkers(container.candlestickSeries, uniqueMarkers);
                    }
                }
            """, getElement(), jsonStr);
        } catch (JsonProcessingException e) {}
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