package com.portfolio.fraud.repository;

import com.portfolio.fraud.entity.*;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PaymentRequestRepository extends JpaRepository<PaymentRequest,Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from PaymentRequest r where r.id=:id")
    Optional<PaymentRequest> lock(@Param("id") Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from PaymentRequest r where r.transactionId=:id")
    Optional<PaymentRequest> lockByTransaction(@Param("id") Long id);
    @Query("select r from PaymentRequest r where ((:sent=true and r.requester.id=:user) or (:sent=false and r.recipient.id=:user)) and (:status is null or (:status=com.portfolio.fraud.entity.PaymentRequestStatus.EXPIRED and (r.status=com.portfolio.fraud.entity.PaymentRequestStatus.EXPIRED or (r.status=com.portfolio.fraud.entity.PaymentRequestStatus.PENDING and r.expiresAt<=:now))) or (r.status=:status and (:status<>com.portfolio.fraud.entity.PaymentRequestStatus.PENDING or r.expiresAt is null or r.expiresAt>:now)))")
    Page<PaymentRequest> search(@Param("user") Long user,@Param("sent") boolean sent,@Param("status") PaymentRequestStatus status,@Param("now") Instant now,Pageable page);
    @Query("select r.id from PaymentRequest r where r.status=com.portfolio.fraud.entity.PaymentRequestStatus.PENDING and r.expiresAt<=:now order by r.id")
    List<Long> expired(@Param("now") Instant now,Pageable page);
}
