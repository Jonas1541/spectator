package com.jonasdurau.spectator.core.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "positions")
public class Position {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String symbol;

    @Column(name = "strategy_name")
    private String strategyName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TradeSide side;

    private double entryPrice;
    private double quantity;
    private Double stopLoss;
    private Double initialStopLoss;
    private Double takeProfit;
    private Double breakevenMultiplier;
    private Double trailingMultiplier;

    @Column(name = "tp1_price")
    private Double tp1Price;

    @Column(name = "tp1_quantity")
    private Double tp1Quantity;

    @Column(name = "tp1_triggered")
    private boolean tp1Triggered = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PositionStatus status;

    private Double realizedPnl;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;
    private Instant closedAt;

    @OneToMany(mappedBy = "position", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Trade> trades = new ArrayList<>();

    public Position() {
    }

    public Position(String symbol, String strategyName, TradeSide side, double entryPrice, double quantity, Double stopLoss,
            Double takeProfit) {
        this.id = UUID.randomUUID();
        this.symbol = symbol;
        this.strategyName = strategyName;
        this.side = side;
        this.entryPrice = entryPrice;
        this.quantity = quantity;
        this.stopLoss = stopLoss;
        this.initialStopLoss = stopLoss;
        this.takeProfit = takeProfit;
        this.status = PositionStatus.OPEN;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // Floating PnL helper - Now simulates Binance USDT-M Futures Taker Fees (0.05% entry + 0.05% exit)
    public double calculateFloatingPnl(double currentPrice) {
        if (status == PositionStatus.CLOSED) {
            return realizedPnl != null ? realizedPnl : 0.0;
        }

        double notionalEntry = entryPrice * quantity;
        double notionalExit = currentPrice * quantity;
        
        // Phase 14: Passive Executions (Maker Orders)
        // Entry is always Maker Limit (0.02%), Exit is typically Taker Market/Stop (0.05%)
        double makerEntryFee = 0.0002;
        double takerExitFee = 0.0005;
        double simulatedFees = (notionalEntry * makerEntryFee) + (notionalExit * takerExitFee);

        if (side == TradeSide.LONG) {
            return (notionalExit - notionalEntry) - simulatedFees;
        } else {
            return (notionalEntry - notionalExit) - simulatedFees;
        }
    }

    public void closePosition(double finalPrice) {
        this.status = PositionStatus.CLOSED;
        this.closedAt = Instant.now();
        this.realizedPnl = calculateFloatingPnl(finalPrice);
    }

    public void addTrade(Trade trade) {
        trade.setPosition(this);
        this.trades.add(trade);
    }

    public void removeTrade(Trade trade) {
        trade.setPosition(null);
        this.trades.remove(trade);
    }

    public List<Trade> getTrades() {
        return Collections.unmodifiableList(trades);
    }

    public UUID getId() {
        return id;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getStrategyName() {
        return strategyName;
    }

    public void setStrategyName(String strategyName) {
        this.strategyName = strategyName;
    }

    public TradeSide getSide() {
        return side;
    }

    public double getEntryPrice() {
        return entryPrice;
    }

    public double getQuantity() {
        return quantity;
    }

    public Double getStopLoss() {
        return stopLoss;
    }

    public Double getInitialStopLoss() {
        return initialStopLoss;
    }

    public void setStopLoss(Double stopLoss) {
        this.stopLoss = stopLoss;
    }

    public Double getTakeProfit() {
        return takeProfit;
    }

    public void setTakeProfit(Double takeProfit) {
        this.takeProfit = takeProfit;
    }

    public Double getBreakevenMultiplier() {
        return breakevenMultiplier;
    }

    public void setBreakevenMultiplier(Double breakevenMultiplier) {
        this.breakevenMultiplier = breakevenMultiplier;
    }

    public Double getTrailingMultiplier() {
        return trailingMultiplier;
    }

    public void setTrailingMultiplier(Double trailingMultiplier) {
        this.trailingMultiplier = trailingMultiplier;
    }

    public Double getTp1Price() {
        return tp1Price;
    }

    public void setTp1Price(Double tp1Price) {
        this.tp1Price = tp1Price;
    }

    public Double getTp1Quantity() {
        return tp1Quantity;
    }

    public void setTp1Quantity(Double tp1Quantity) {
        this.tp1Quantity = tp1Quantity;
    }

    public boolean isTp1Triggered() {
        return tp1Triggered;
    }

    public void setTp1Triggered(boolean tp1Triggered) {
        this.tp1Triggered = tp1Triggered;
    }

    public void setQuantity(double quantity) {
        this.quantity = quantity;
    }

    public PositionStatus getStatus() {
        return status;
    }

    public void setStatus(PositionStatus status) {
        this.status = status;
    }

    public Double getRealizedPnl() {
        return realizedPnl;
    }

    public void setRealizedPnl(Double realizedPnl) {
        this.realizedPnl = realizedPnl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
