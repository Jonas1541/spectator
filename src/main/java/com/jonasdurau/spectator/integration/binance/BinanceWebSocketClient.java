package com.jonasdurau.spectator.integration.binance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.integration.binance.dto.BinanceKlineEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.util.function.Consumer;

@Component
public class BinanceWebSocketClient extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(BinanceWebSocketClient.class);
    private static final String BINANCE_WS_URL = "wss://stream.binance.com:9443/ws/";

    private final ObjectMapper objectMapper;
    private Consumer<Candle> candleUpdateListener;
    private WebSocketSession currentSession;

    public BinanceWebSocketClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Inicia a conexão com a Binance para um símbolo e intervalo específicos.
     * @param symbol Ex: "btcusdt" (A Binance exige minúsculo no WebSocket)
     * @param interval Ex: "1h", "4h"
     * @param listener Uma função que será chamada toda vez que um tick chegar
     */
    public void connect(String symbol, String interval, Consumer<Candle> listener) {
        this.candleUpdateListener = listener;
        String streamUrl = BINANCE_WS_URL + symbol.toLowerCase() + "@kline_" + interval;

        StandardWebSocketClient client = new StandardWebSocketClient();
        try {
            log.info("Connecting to Binance WebSocket: {}", streamUrl);
            client.execute(this, streamUrl).get(); // .get() trava até conectar
        } catch (Exception e) {
            log.error("Failed to connect to Binance WebSocket", e);
            // Em produção, implementaríamos um retry automático aqui
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        this.currentSession = session;
        log.info("Binance WebSocket Connection Established. Session ID: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        
        // Converte o JSON string para o nosso Record
        BinanceKlineEvent event = objectMapper.readValue(payload, BinanceKlineEvent.class);
        BinanceKlineEvent.KlineData data = event.kline();

        // Converte o DTO da Binance para nossa Entidade de Domínio
        Instant time = Instant.ofEpochMilli(data.startTime());
        
        Candle candle = new Candle(
                event.symbol(),
                time,
                Double.parseDouble(data.open()),
                Double.parseDouble(data.high()),
                Double.parseDouble(data.low()),
                Double.parseDouble(data.close()),
                Double.parseDouble(data.volume())
        );

        // Se alguém estiver escutando, repassa o candle
        if (candleUpdateListener != null) {
            candleUpdateListener.accept(candle);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.warn("Binance WebSocket Connection Closed. Status: {}", status);
        this.currentSession = null;
    }
}