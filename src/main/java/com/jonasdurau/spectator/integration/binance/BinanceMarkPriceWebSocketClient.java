package com.jonasdurau.spectator.integration.binance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jonasdurau.spectator.core.service.OrderFlowService;
import com.jonasdurau.spectator.integration.binance.dto.BinanceMarkPriceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
@ConditionalOnProperty(name = "spectator.mode.backtest-only", havingValue = "false", matchIfMissing = true)
public class BinanceMarkPriceWebSocketClient extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(BinanceMarkPriceWebSocketClient.class);
    private static final String BINANCE_WS_URL = "wss://fstream.binance.com/ws/";

    private final ObjectMapper objectMapper;
    private final OrderFlowService orderFlowService;
    private String connectedSymbol;

    public BinanceMarkPriceWebSocketClient(ObjectMapper objectMapper, OrderFlowService orderFlowService) {
        this.objectMapper = objectMapper;
        this.orderFlowService = orderFlowService;
    }

    public void connect(String symbol) {
        this.connectedSymbol = symbol.toUpperCase();
        String url = BINANCE_WS_URL + symbol.toLowerCase() + "@markPrice@1s";
        StandardWebSocketClient client = new StandardWebSocketClient();
        client.execute(this, url);
        log.info("Connecting to Binance MarkPrice WebSocket: {}", url);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("Binance MarkPrice WebSocket Connection Established for {}.", connectedSymbol);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        BinanceMarkPriceEvent event = objectMapper.readValue(message.getPayload(), BinanceMarkPriceEvent.class);
        if (event.fundingRate() != null && !event.fundingRate().isEmpty()) {
            double fundingRate = Double.parseDouble(event.fundingRate());
            orderFlowService.updateFundingRate(connectedSymbol, fundingRate);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.warn("Binance MarkPrice WebSocket Connection Closed for {}. Status: {}", connectedSymbol, status);
    }
}
