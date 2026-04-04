package com.jonasdurau.spectator.core.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Serviço de métricas customizadas do motor de trading.
 * Registra contadores e gauges no Micrometer para exposição via /actuator/prometheus.
 * Métricas disponíveis:
 *   spectator.positions.opened — posições abertas
 *   spectator.positions.closed — posições fechadas
 *   spectator.positions.stopped — SL hits
 *   spectator.positions.tp_hit — TP hits
 *   spectator.pnl.realized — PnL total acumulado (gauge)
 *   spectator.orders.live.success — ordens live executadas
 *   spectator.orders.live.failed — ordens live falhadas
 *   spectator.websocket.reconnects — reconexões WS
 */
@Service
public class TradingMetricsService {

    private final Counter positionsOpened;
    private final Counter positionsClosed;
    private final Counter stopLossHits;
    private final Counter takeProfitHits;
    private final Counter panicCloses;
    private final Counter tp1Hits;
    private final Counter liveOrdersSuccess;
    private final Counter liveOrdersFailed;
    private final Counter websocketReconnects;
    private final AtomicReference<Double> cumulativePnl = new AtomicReference<>(0.0);

    public TradingMetricsService(MeterRegistry registry) {
        this.positionsOpened = Counter.builder("spectator.positions.opened")
                .description("Total de posições abertas pelo motor de trading")
                .register(registry);

        this.positionsClosed = Counter.builder("spectator.positions.closed")
                .description("Total de posições fechadas")
                .register(registry);

        this.stopLossHits = Counter.builder("spectator.positions.stopped")
                .description("Total de stop losses disparados")
                .register(registry);

        this.takeProfitHits = Counter.builder("spectator.positions.tp_hit")
                .description("Total de take profits disparados")
                .register(registry);

        this.panicCloses = Counter.builder("spectator.positions.panic_close")
                .description("Total de fechamentos por mudança de regime (panic close)")
                .register(registry);

        this.tp1Hits = Counter.builder("spectator.positions.tp1_hit")
                .description("Total de partial take profits (TP1) disparados")
                .register(registry);

        this.liveOrdersSuccess = Counter.builder("spectator.orders.live.success")
                .description("Total de ordens live executadas com sucesso")
                .register(registry);

        this.liveOrdersFailed = Counter.builder("spectator.orders.live.failed")
                .description("Total de ordens live que falharam")
                .register(registry);

        this.websocketReconnects = Counter.builder("spectator.websocket.reconnects")
                .description("Total de reconexões WebSocket")
                .register(registry);

        // Gauge do PnL acumulado — lê o AtomicReference
        registry.gauge("spectator.pnl.realized", cumulativePnl, AtomicReference::get);
    }

    public void recordPositionOpened() {
        positionsOpened.increment();
    }

    public void recordPositionClosed(double realizedPnl) {
        positionsClosed.increment();
        cumulativePnl.accumulateAndGet(realizedPnl, Double::sum);
    }

    public void recordStopLossHit() {
        stopLossHits.increment();
    }

    public void recordTakeProfitHit() {
        takeProfitHits.increment();
    }

    public void recordPanicClose() {
        panicCloses.increment();
    }

    public void recordTp1Hit() {
        tp1Hits.increment();
    }

    public void recordLiveOrderSuccess() {
        liveOrdersSuccess.increment();
    }

    public void recordLiveOrderFailed() {
        liveOrdersFailed.increment();
    }

    public void recordWebSocketReconnect() {
        websocketReconnects.increment();
    }
}
