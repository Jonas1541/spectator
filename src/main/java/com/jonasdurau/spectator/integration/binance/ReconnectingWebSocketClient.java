package com.jonasdurau.spectator.integration.binance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Classe base para clientes WebSocket da Binance com reconexão automática.
 * Implementa backoff exponencial (1s → 2s → 4s → 8s → ... → 60s cap).
 * Subclasses fornecem a URL do stream e o processamento de mensagens.
 */
public abstract class ReconnectingWebSocketClient extends TextWebSocketHandler {

    private static final long INITIAL_DELAY_MS = 1000;
    private static final long MAX_DELAY_MS = 60_000;
    private static final double BACKOFF_MULTIPLIER = 2.0;

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private volatile WebSocketSession currentSession;
    private volatile boolean shouldReconnect = true;
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final ScheduledExecutorService reconnectScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ws-reconnect-" + getClass().getSimpleName());
                t.setDaemon(true);
                return t;
            });

    /**
     * Subclasses devem retornar a URL completa do stream WebSocket.
     * Ex: wss://fstream.binance.com/ws/btcusdt@kline_1h
     */
    protected abstract String buildStreamUrl();

    /**
     * Inicia a conexão com a Binance. Chamada inicial e também pela rotina de reconexão.
     */
    public void connect() {
        String streamUrl = buildStreamUrl();
        StandardWebSocketClient client = new StandardWebSocketClient();
        try {
            log.info("Connecting to Binance WebSocket: {}", streamUrl);
            client.execute(this, streamUrl).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Failed to connect to Binance WebSocket: {}", streamUrl, e);
            if (shouldReconnect) {
                scheduleReconnect();
            }
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        this.currentSession = session;
        int attempts = reconnectAttempts.getAndSet(0);
        if (attempts > 0) {
            log.info("✅ Binance WebSocket RECONNECTED after {} attempts. Session: {}", attempts, session.getId());
        } else {
            log.info("Binance WebSocket Connection Established. Session: {}", session.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        this.currentSession = null;
        log.warn("Binance WebSocket Connection Closed. Status: {}. URL: {}", status, buildStreamUrl());
        if (shouldReconnect) {
            scheduleReconnect();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Binance WebSocket Transport Error on {}: {}", buildStreamUrl(), exception.getMessage());
        try {
            if (session.isOpen()) {
                session.close();
            }
        } catch (Exception e) {
            log.debug("Error closing session after transport error", e);
        }
        // afterConnectionClosed será chamado automaticamente e agendará o reconnect
    }

    /**
     * Agenda uma reconexão com backoff exponencial.
     * Delay: 1s, 2s, 4s, 8s, 16s, 32s, 60s (cap)
     */
    private void scheduleReconnect() {
        int attempt = reconnectAttempts.incrementAndGet();
        long delayMs = (long) (INITIAL_DELAY_MS * Math.pow(BACKOFF_MULTIPLIER, attempt - 1));
        delayMs = Math.min(delayMs, MAX_DELAY_MS);

        log.warn("⏳ Scheduling WebSocket reconnect attempt #{} in {}ms for: {}", attempt, delayMs, buildStreamUrl());

        reconnectScheduler.schedule(() -> {
            if (shouldReconnect) {
                log.info("🔄 Attempting WebSocket reconnect #{} for: {}", attempt, buildStreamUrl());
                connect();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Encerra a conexão e desabilita reconexão automática.
     * Usar durante shutdown da aplicação.
     */
    public void disconnect() {
        shouldReconnect = false;
        reconnectScheduler.shutdownNow();
        if (currentSession != null && currentSession.isOpen()) {
            try {
                currentSession.close();
            } catch (Exception e) {
                log.debug("Error closing WebSocket session during disconnect", e);
            }
        }
        log.info("WebSocket client disconnected and reconnect disabled for: {}", buildStreamUrl());
    }

    protected WebSocketSession getCurrentSession() {
        return currentSession;
    }
}
