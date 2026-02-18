package com.jonasdurau.spectator.core.domain;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.Objects;

public class CandleId implements Serializable {
    private String symbol;
    private ZonedDateTime time;

    public CandleId() {
    }

    public CandleId(String symbol, ZonedDateTime time) {
        this.symbol = symbol;
        this.time = time;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public ZonedDateTime getTime() {
        return time;
    }

    public void setTime(ZonedDateTime time) {
        this.time = time;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        CandleId candleId = (CandleId) o;
        return Objects.equals(symbol, candleId.symbol) && Objects.equals(time, candleId.time);
    }

    @Override
    public int hashCode() {
        return Objects.hash(symbol, time);
    }
}
