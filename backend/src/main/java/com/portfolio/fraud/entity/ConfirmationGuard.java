package com.portfolio.fraud.entity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="confirmation_guards")
public class ConfirmationGuard {
    @Id @Column(name="user_id") private Long userId;
    @Column(name="failed_attempts",nullable=false) private int failedAttempts;
    @Column(name="window_started_at",nullable=false) private Instant windowStartedAt;
    @Column(name="locked_until") private Instant lockedUntil;
    protected ConfirmationGuard() {}
    public ConfirmationGuard(Long userId,Instant now) { this.userId=userId; windowStartedAt=now; }
    public boolean isLocked(Instant now) { return lockedUntil!=null && now.isBefore(lockedUntil); }
    public void failed(Instant now,int maximum,long windowSeconds,long lockSeconds) {
        if (!now.isBefore(windowStartedAt.plusSeconds(windowSeconds))) {
            failedAttempts=0; windowStartedAt=now;
        }
        failedAttempts++;
        if (failedAttempts>=maximum) lockedUntil=now.plusSeconds(lockSeconds);
    }
    public void succeeded(Instant now) { failedAttempts=0; lockedUntil=null; windowStartedAt=now; }
    public int getFailedAttempts() { return failedAttempts; }
}
