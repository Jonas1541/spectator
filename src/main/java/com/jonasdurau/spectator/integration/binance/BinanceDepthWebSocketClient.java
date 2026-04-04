package com.jonasdurau.spectator.integration.binance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jonasdurau.spectator.core.service.OrderBookService;
import com.jonasdurau.spectator.core.service.OrderBookService.PriceLevel;
import com.jonasdurau.spectator.integration.binance.dto.BinanceDepthEvent;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Cliente WebSocket para o stream de Order Book (Depth) da Binance Futures.
 * Herda reconexão automática com backoff exponencial de ReconnectingWebSocketClient.
 */
public class BinanceDepthWebSocketClient extends ReconnectingWebSocketClient {

    private static final String BINANCE_WS_URL = "wss://fstream.binance.com/ws/";

    private final ObjectMapper objectMapper;
    private final OrderBookService orderBookService;
    private final String symbol;

    public BinanceDepthWebSocketClient(ObjectMapper objectMapper, OrderBookService orderBookService, String symbol) {
        this.objectMapper = objectMapper;
        this.orderBookService = orderBookService;
        this.symbol = symbol;
    }

    @Override
    protected String buildStreamUrl() {
        return BINANCE_WS_URL + symbol.toLowerCase() + "@depth5@100ms";
    }

    @Override
    protected void handleTextMessage(org.springframework.web.socket.WebSocketSession session,
                                     org.springframework.web.socket.TextMessage message) throws Exception {
        String payload = message.getPayload();
        BinanceDepthEvent event = objectMapper.readValue(payload, BinanceDepthEvent.class);

        List<PriceLevel> mappedBids = event.bids().stream()
                .map(entry -> new PriceLevel(Double.parseDouble(entry.get(0)), Double.parseDouble(entry.get(1))))
                .collect(Collectors.toList());

        List<PriceLevel> mappedAsks = event.asks().stream()
                .map(entry -> new PriceLevel(Double.parseDouble(entry.get(0)), Double.parseDouble(entry.get(1))))
                .collect(Collectors.toList());

        orderBookService.updateOrderBook(symbol.toUpperCase(), mappedBids, mappedAsks);
    }
}
