package com.portfolio.fraud.repository;
import com.portfolio.fraud.entity.FraudAnalysis;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface FraudAnalysisRepository extends JpaRepository<FraudAnalysis,Long> {
    @EntityGraph(attributePaths="ruleResults")
    @Query("select a from FraudAnalysis a where a.transaction.id=:id and a.phase='ORIGINAL'")
    Optional<FraudAnalysis> findByTransactionId(@Param("id") Long id);
    @EntityGraph(attributePaths="ruleResults")
    Optional<FraudAnalysis> findByTransactionIdAndPhase(Long id,String phase);
}
