package com.jonasdurau.spectator.core.domain;

public enum TradeSignal {
    BUY,
    SELL,
    HOLD,       // Não fazer nada (maioria do tempo)
    CLOSE_ALL   // Pânico ou saída de emergência
}
