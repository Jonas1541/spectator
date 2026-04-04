package com.jonasdurau.spectator.integration.binance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.integration.binance.dto.BinanceKlineEvent;

import java.time.Instant;
import java.util.function.BiConsumer;

/**
 * Cliente WebSocket para o stream de Klines (candles) da Binance Futures.
 * Herda reconexão automática com backoff exponencial de ReconnectingWebSocketClient.
 */
public class BinanceWebSocketClient extends ReconnectingWebSocketClient {

    private static final String BINANCE_WS_URL = "wss://fstream.binance.com/ws/";

    private final ObjectMapper objectMapper;
    private final BiConsumer<Candle, Boolean> candleUpdateListener;
    private final String symbol;
    private final String interval;

    public BinanceWebSocketClient(ObjectMapper objectMapper, BiConsumer<Candle, Boolean> listener, String symbol, String interval) {
        this.objectMapper = objectMapper;
        this.candleUpdateListener = listener;
        this.symbol = symbol;
        this.interval = interval;
    }

    @Override
    protected String buildStreamUrl() {
        return BINANCE_WS_URL + symbol.toLowerCase() + "@kline_" + interval;
    }

    @Override
    protected void handleTextMessage(org.springframework.web.socket.WebSocketSession session,
                                     org.springframework.web.socket.TextMessage message) throws Exception {
        String payload = message.getPayload();

        BinanceKlineEvent event = objectMapper.readValue(payload, BinanceKlineEvent.class);
        BinanceKlineEvent.KlineData data = event.kline();

        Instant time = Instant.ofEpochMilli(data.startTime());

        Candle candle = new Candle(
                event.symbol(),
                data.interval(),
                time,
                Double.parseDouble(data.open()),
                Double.parseDouble(data.high()),
                Double.parseDouble(data.low()),
                Double.parseDouble(data.close()),
                Double.parseDouble(data.volume()),
                Double.parseDouble(data.quoteAssetVolume()),
                Double.parseDouble(data.takerBuyBaseAssetVolume())
        );

        if (candleUpdateListener != null) {
            candleUpdateListener.accept(candle, data.isClosed());
        }
    }
}