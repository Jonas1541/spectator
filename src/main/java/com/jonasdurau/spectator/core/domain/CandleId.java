package com.jonasdurau.spectator.core.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

public class CandleId implements Serializable {
    private String symbol;
    private String timeframe;
    private Instant time;

    public CandleId() {
    }

    public CandleId(String symbol, String timeframe, Instant time) {
        this.symbol = symbol;
        this.timeframe = timeframe;
        this.time = time;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public void setTimeframe(String timeframe) {
        this.timeframe = timeframe;
    }

    public Instant getTime() {
        return time;
    }

    public void setTime(Instant time) {
        this.time = time;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        CandleId candleId = (CandleId) o;
        return Objects.equals(symbol, candleId.symbol) && Objects.equals(timeframe, candleId.timeframe) && Objects.equals(time, candleId.time);
    }

    @Override
    public int hashCode() {
        return Objects.hash(symbol, timeframe, time);
    }
}
