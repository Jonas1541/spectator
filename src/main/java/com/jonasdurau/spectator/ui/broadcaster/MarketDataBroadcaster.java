package com.jonasdurau.spectator.ui.broadcaster;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class MarketDataBroadcaster {

    // Lista thread-safe para guardar todas as abas de navegador abertas
    private final List<Consumer<MarketTick>> listeners = new CopyOnWriteArrayList<>();
    
    // Cache thread-safe para armazenar o último tick de cada moeda (State Holder)
    private final Map<String, MarketTick> lastTicks = new ConcurrentHashMap<>();

    public void register(Consumer<MarketTick> listener) {
        listeners.add(listener);
        // Emite imediatamente o último estado conhecido de todas as moedas para a nova aba
        for (MarketTick tick : lastTicks.values()) {
            listener.accept(tick);
        }
    }

    public void unregister(Consumer<MarketTick> listener) {
        listeners.remove(listener);
    }

    public void broadcast(MarketTick tick) {
        // Guarda o tick atual no cache antes de enviar para os listeners
        lastTicks.put(tick.candle().getSymbol(), tick);
        for (Consumer<MarketTick> listener : listeners) {
            listener.accept(tick);
        }
    }
}