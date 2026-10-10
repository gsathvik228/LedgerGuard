package com.portfolio.fraud.repository;
import com.portfolio.fraud.entity.ConfirmationGuard;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ConfirmationGuardRepository extends JpaRepository<ConfirmationGuard,Long> {
    @Modifying
    @Query(value="INSERT IGNORE INTO confirmation_guards (user_id,failed_attempts,window_started_at) VALUES (:id,0,:now)",nativeQuery=true)
    void ensureExists(@Param("id") Long id,@Param("now") Instant now);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from ConfirmationGuard g where g.userId=:id")
    ConfirmationGuard lock(@Param("id") Long id);
}
