package com.portfolio.fraud.repository;

import com.portfolio.fraud.entity.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<Transaction,Long> {
    @Query("""
        select t from Transaction t
        left join t.senderAccount s left join t.recipientAccount r
        where (:userId is null or t.initiatedBy.id=:userId or s.user.id=:userId or r.user.id=:userId)
        and (:type is null or t.type=:type)
        and (:status is null or t.status=:status)
        and (:risk is null or t.riskLevel=:risk)
        and (:from is null or t.createdAt>=:from)
        and (:to is null or t.createdAt<=:to)
        and (:suspicious=false or t.riskLevel in (com.portfolio.fraud.entity.RiskLevel.HIGH, com.portfolio.fraud.entity.RiskLevel.CRITICAL))
        """)
    Page<Transaction> search(@Param("userId") Long userId, @Param("type") TransactionType type,
        @Param("status") TransactionStatus status, @Param("risk") RiskLevel risk,
        @Param("from") Instant from, @Param("to") Instant to,
        @Param("suspicious") boolean suspicious, Pageable page);
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transaction t where t.id=:id")
    Optional<Transaction> findByIdForUpdate(@Param("id") Long id);
    @Query("select count(t) from Transaction t where t.initiatedBy.id=:id and t.createdAt>:after and t.id<>:exclude")
    long countActivityExcluding(@Param("id") Long id,@Param("after") Instant after,@Param("exclude") Long exclude);
    @Query("select count(t) from Transaction t where t.senderAccount.id=:sender and t.recipientAccount.id=:recipient and t.createdAt>:after and t.id<>:exclude")
    long countRecipientExcluding(@Param("sender") Long sender,@Param("recipient") Long recipient,@Param("after") Instant after,@Param("exclude") Long exclude);
    @Query("select avg(t.amount) from Transaction t where t.initiatedBy.id=:id and t.status=com.portfolio.fraud.entity.TransactionStatus.COMPLETED and t.id<>:exclude")
    BigDecimal averageExcluding(@Param("id") Long id,@Param("exclude") Long exclude);
    long countByInitiatedByIdAndCreatedAtAfter(Long id, Instant after);
    long countBySenderAccountIdAndRecipientAccountIdAndCreatedAtAfter(Long sender, Long recipient, Instant after);
    long countByStatus(TransactionStatus status);
    long countByType(TransactionType type);
    long countByRiskLevel(RiskLevel risk);
    @Query("select avg(t.amount) from Transaction t where t.initiatedBy.id=:id and t.status=com.portfolio.fraud.entity.TransactionStatus.COMPLETED")
    BigDecimal averageCompletedAmount(@Param("id") Long id);
    @Query("""
        select coalesce(s.currency,r.currency), sum(t.amount) from Transaction t
        left join t.senderAccount s left join t.recipientAccount r
        where t.status=com.portfolio.fraud.entity.TransactionStatus.COMPLETED
        group by coalesce(s.currency,r.currency)
        """)
    List<Object[]> completedVolumeByCurrency();
}
