package com.jonasdurau.spectator.core.domain;

public enum MarketRegime {
    TRENDING_UP,      // Preço > EMA200 && ADX > 20
    TRENDING_DOWN,    // Preço < EMA200 && ADX > 20
    SIDEWAYS,         // ADX < 20 (Lateral/Consolidação)
    VOLATILE          // ATR explodindo (Perigo/Transição)
}
