package com.jonasdurau.spectator.integration.binance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jonasdurau.spectator.core.service.OrderFlowService;
import com.jonasdurau.spectator.integration.binance.dto.BinanceAggTradeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class BinanceAggTradeWebSocketClient extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(BinanceAggTradeWebSocketClient.class);
    private static final String BINANCE_WS_URL = "wss://fstream.binance.com/ws/";

    private final ObjectMapper objectMapper;
    private final OrderFlowService orderFlowService;
    private String connectedSymbol;

    public BinanceAggTradeWebSocketClient(ObjectMapper objectMapper, OrderFlowService orderFlowService) {
        this.objectMapper = objectMapper;
        this.orderFlowService = orderFlowService;
    }

    public void connect(String symbol) {
        this.connectedSymbol = symbol.toUpperCase();
        String url = BINANCE_WS_URL + symbol.toLowerCase() + "@aggTrade";
        StandardWebSocketClient client = new StandardWebSocketClient();
        client.execute(this, url);
        log.info("Connecting to Binance AggTrade WebSocket: {}", url);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("Binance AggTrade WebSocket Connection Established for {}.", connectedSymbol);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        BinanceAggTradeEvent event = objectMapper.readValue(message.getPayload(), BinanceAggTradeEvent.class);
        if (event.quantity() != null) {
            double quantity = Double.parseDouble(event.quantity());
            orderFlowService.registerTrade(connectedSymbol, quantity, event.isBuyerMaker());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.warn("Binance AggTrade WebSocket Connection Closed for {}. Status: {}", connectedSymbol, status);
    }
}
