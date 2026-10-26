package com.portfolio.fraud.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity @Table(name="payment_requests")
public class PaymentRequest {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="requester_id",nullable=false) private User requester;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="recipient_id",nullable=false) private User recipient;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="receiving_account_id",nullable=false) private Account receivingAccount;
    @Column(nullable=false,precision=19,scale=2) private BigDecimal amount;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    @Column(nullable=false,length=3) private String currency;
    @Column(nullable=false,length=255) private String description;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private PaymentRequestStatus status=PaymentRequestStatus.PENDING;
    @Column(name="transaction_id") private Long transactionId;
    @Column(name="expires_at") private Instant expiresAt;
    @Column(name="resolved_at") private Instant resolvedAt;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @Version private Long version;

    protected PaymentRequest() {}
    public PaymentRequest(User requester,User recipient,Account receivingAccount,BigDecimal amount,String description,Instant expiresAt,Instant now) {
        this.requester=requester;this.recipient=recipient;this.receivingAccount=receivingAccount;
        this.amount=amount;this.currency=receivingAccount.getCurrency();this.description=description;
        this.expiresAt=expiresAt;createdAt=now;updatedAt=now;
    }
    public Long getId(){return id;} public User getRequester(){return requester;} public User getRecipient(){return recipient;}
    public Account getReceivingAccount(){return receivingAccount;} public BigDecimal getAmount(){return amount;}
    public String getCurrency(){return currency;} public String getDescription(){return description;}
    public PaymentRequestStatus getStatus(){return status;} public Long getTransactionId(){return transactionId;}
    public Instant getExpiresAt(){return expiresAt;} public Instant getResolvedAt(){return resolvedAt;}
    public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
    public boolean deadlinePassed(Instant now){return expiresAt!=null&&!expiresAt.isAfter(now);}
    public void resolve(PaymentRequestStatus next,Instant now) {
        if(status!=PaymentRequestStatus.PENDING || (next!=PaymentRequestStatus.DECLINED&&next!=PaymentRequestStatus.CANCELLED&&next!=PaymentRequestStatus.EXPIRED))
            throw new IllegalStateException("Invalid payment request transition");
        status=next;resolvedAt=now;updatedAt=now;
    }
    public void attach(Long transactionId,TransactionStatus outcome,Instant now) {
        if(status!=PaymentRequestStatus.PENDING)throw new IllegalStateException("Request is not payable");
        this.transactionId=transactionId;status=PaymentRequestStatus.PAYMENT_PENDING;updatedAt=now;synchronize(outcome,now);
    }
    public boolean synchronize(TransactionStatus outcome,Instant now) {
        if(status!=PaymentRequestStatus.PAYMENT_PENDING)return false;
        PaymentRequestStatus next=switch(outcome) {
            case COMPLETED -> PaymentRequestStatus.PAID;
            case PENDING_CONFIRMATION, AWAITING_REVIEW -> PaymentRequestStatus.PAYMENT_PENDING;
            default -> deadlinePassed(now)?PaymentRequestStatus.EXPIRED:PaymentRequestStatus.PENDING;
        };
        if(next==status)return false;
        status=next;updatedAt=now;resolvedAt=(next==PaymentRequestStatus.PAID||next==PaymentRequestStatus.EXPIRED)?now:null;return true;
    }
}
