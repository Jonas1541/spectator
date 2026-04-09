package com.jonasdurau.spectator.core.repository;

import com.jonasdurau.spectator.core.domain.Position;
import com.jonasdurau.spectator.core.domain.PositionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PositionRepository extends JpaRepository<Position, UUID> {
    List<Position> findBySymbolAndStatus(String symbol, PositionStatus status);

    List<Position> findByStatus(PositionStatus status);

    List<Position> findBySymbolAndStatusOrderByClosedAtAsc(String symbol, PositionStatus status);

    List<Position> findByStatusOrderByClosedAtAsc(PositionStatus status);

    int countBySymbolAndStatus(String symbol, PositionStatus status);

    int countByStatus(PositionStatus status);

    int countByStrategyNameAndStatus(String strategyName, PositionStatus status);
    
    int countByStrategyNameAndStatusAndRealizedPnlGreaterThan(String strategyName, PositionStatus status, Double pnl);
}
