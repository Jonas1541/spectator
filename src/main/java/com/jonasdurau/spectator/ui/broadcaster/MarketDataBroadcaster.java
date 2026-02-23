package com.jonasdurau.spectator.ui.broadcaster;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class MarketDataBroadcaster {

    // Lista thread-safe para guardar todas as abas de navegador abertas
    private final List<Consumer<MarketTick>> listeners = new CopyOnWriteArrayList<>();

    public void register(Consumer<MarketTick> listener) {
        listeners.add(listener);
    }

    public void unregister(Consumer<MarketTick> listener) {
        listeners.remove(listener);
    }

    public void broadcast(MarketTick tick) {
        for (Consumer<MarketTick> listener : listeners) {
            listener.accept(tick);
        }
    }
}