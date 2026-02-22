package com.jonasdurau.spectator.core.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "market_candles")
@IdClass(CandleId.class) // Necessário para chave composta (Symbol + Time)
public class Candle {

    @Id
    private String symbol;

    @Id
    private Instant time;

    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;

    public Candle() {
    }

    public Candle(String symbol, Instant time, double open, double high, double low, double close, double volume) {
        this.symbol = symbol;
        this.time = time;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    public String getSymbol() {
        return symbol;
    }

    public Instant getTime() {
        return time;
    }

    public double getOpen() {
        return open;
    }

    public double getHigh() {
        return high;
    }

    public double getLow() {
        return low;
    }

    public double getClose() {
        return close;
    }

    public double getVolume() {
        return volume;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public void setTime(Instant time) {
        this.time = time;
    }

    public void setOpen(double open) {
        this.open = open;
    }

    public void setHigh(double high) {
        this.high = high;
    }

    public void setLow(double low) {
        this.low = low;
    }

    public void setClose(double close) {
        this.close = close;
    }

    public void setVolume(double volume) {
        this.volume = volume;
    }

    public boolean isBullish() {
        return close > open;
    }

    public boolean isBearish() {
        return close < open;
    }

    public double getBodySize() {
        return Math.abs(close - open);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Candle candle = (Candle) o;
        return Objects.equals(symbol, candle.symbol) && Objects.equals(time, candle.time);
    }

    @Override
    public int hashCode() {
        return Objects.hash(symbol, time);
    }

    @Override
    public String toString() {
        return "Candle{" +
                "symbol='" + symbol + '\'' +
                ", time=" + time +
                ", o=" + open +
                ", h=" + high +
                ", l=" + low +
                ", c=" + close +
                '}';
    }
}
