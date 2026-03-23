package com.jonasdurau.spectator.integration.binance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jonasdurau.spectator.core.service.OrderBookService;
import com.jonasdurau.spectator.core.service.OrderBookService.PriceLevel;
import com.jonasdurau.spectator.integration.binance.dto.BinanceDepthEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.stream.Collectors;

public class BinanceDepthWebSocketClient extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(BinanceDepthWebSocketClient.class);
    private static final String BINANCE_WS_URL = "wss://fstream.binance.com/ws/";

    private final ObjectMapper objectMapper;
    private final OrderBookService orderBookService;
    private String connectedSymbol;

    public BinanceDepthWebSocketClient(ObjectMapper objectMapper, OrderBookService orderBookService) {
        this.objectMapper = objectMapper;
        this.orderBookService = orderBookService;
    }

    public void connect(String symbol) {
        this.connectedSymbol = symbol.toUpperCase();
        String streamUrl = BINANCE_WS_URL + symbol.toLowerCase() + "@depth5@100ms";

        StandardWebSocketClient client = new StandardWebSocketClient();
        try {
            log.info("Connecting to Binance Depth WebSocket: {}", streamUrl);
            client.execute(this, streamUrl).get();
        } catch (Exception e) {
            log.error("Failed to connect to Binance Depth WebSocket", e);
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("Binance Depth WebSocket Connection Established. Session ID: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        BinanceDepthEvent event = objectMapper.readValue(payload, BinanceDepthEvent.class);

        List<PriceLevel> mappedBids = event.bids().stream()
                .map(entry -> new PriceLevel(Double.parseDouble(entry.get(0)), Double.parseDouble(entry.get(1))))
                .collect(Collectors.toList());

        List<PriceLevel> mappedAsks = event.asks().stream()
                .map(entry -> new PriceLevel(Double.parseDouble(entry.get(0)), Double.parseDouble(entry.get(1))))
                .collect(Collectors.toList());

        orderBookService.updateOrderBook(connectedSymbol, mappedBids, mappedAsks);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.warn("Binance Depth WebSocket Connection Closed. Status: {}", status);
    }
}
