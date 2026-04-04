package com.jonasdurau.spectator.integration.binance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jonasdurau.spectator.core.service.OrderFlowService;
import com.jonasdurau.spectator.integration.binance.dto.BinanceAggTradeEvent;

import java.util.function.BiConsumer;

/**
 * Cliente WebSocket para o stream de Aggregated Trades da Binance Futures.
 * Herda reconexão automática com backoff exponencial de ReconnectingWebSocketClient.
 */
public class BinanceAggTradeWebSocketClient extends ReconnectingWebSocketClient {

    private static final String BINANCE_WS_URL = "wss://fstream.binance.com/ws/";

    private final ObjectMapper objectMapper;
    private final OrderFlowService orderFlowService;
    private final BiConsumer<String, Double> priceTickListener;
    private final String symbol;

    public BinanceAggTradeWebSocketClient(ObjectMapper objectMapper, OrderFlowService orderFlowService,
                                          BiConsumer<String, Double> priceTickListener, String symbol) {
        this.objectMapper = objectMapper;
        this.orderFlowService = orderFlowService;
        this.priceTickListener = priceTickListener;
        this.symbol = symbol;
    }

    @Override
    protected String buildStreamUrl() {
        return BINANCE_WS_URL + symbol.toLowerCase() + "@aggTrade";
    }

    @Override
    protected void handleTextMessage(org.springframework.web.socket.WebSocketSession session,
                                     org.springframework.web.socket.TextMessage message) throws Exception {
        BinanceAggTradeEvent event = objectMapper.readValue(message.getPayload(), BinanceAggTradeEvent.class);
        if (event.quantity() != null && event.price() != null) {
            double quantity = Double.parseDouble(event.quantity());
            double price = Double.parseDouble(event.price());
            orderFlowService.registerTrade(symbol.toUpperCase(), quantity, event.isBuyerMaker());

            if (priceTickListener != null) {
                priceTickListener.accept(symbol.toUpperCase(), price);
            }
        }
    }
}
