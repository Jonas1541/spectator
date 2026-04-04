package com.jonasdurau.spectator.integration.binance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jonasdurau.spectator.core.service.OrderFlowService;
import com.jonasdurau.spectator.integration.binance.dto.BinanceMarkPriceEvent;

/**
 * Cliente WebSocket para o stream de Mark Price da Binance Futures.
 * Herda reconexão automática com backoff exponencial de ReconnectingWebSocketClient.
 */
public class BinanceMarkPriceWebSocketClient extends ReconnectingWebSocketClient {

    private static final String BINANCE_WS_URL = "wss://fstream.binance.com/ws/";

    private final ObjectMapper objectMapper;
    private final OrderFlowService orderFlowService;
    private final String symbol;

    public BinanceMarkPriceWebSocketClient(ObjectMapper objectMapper, OrderFlowService orderFlowService, String symbol) {
        this.objectMapper = objectMapper;
        this.orderFlowService = orderFlowService;
        this.symbol = symbol;
    }

    @Override
    protected String buildStreamUrl() {
        return BINANCE_WS_URL + symbol.toLowerCase() + "@markPrice@1s";
    }

    @Override
    protected void handleTextMessage(org.springframework.web.socket.WebSocketSession session,
                                     org.springframework.web.socket.TextMessage message) throws Exception {
        BinanceMarkPriceEvent event = objectMapper.readValue(message.getPayload(), BinanceMarkPriceEvent.class);
        if (event.fundingRate() != null && !event.fundingRate().isEmpty()) {
            double fundingRate = Double.parseDouble(event.fundingRate());
            orderFlowService.updateFundingRate(symbol.toUpperCase(), fundingRate);
        }
    }
}
