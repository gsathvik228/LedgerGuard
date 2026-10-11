package com.portfolio.fraud.entity;
import jakarta.persistence.*; import java.math.BigDecimal; import java.time.Instant;
@Entity @Table(name="transactions") public class Transaction { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="sender_account_id") private Account senderAccount; @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="recipient_account_id") private Account recipientAccount; @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="initiated_by_user_id",nullable=false) private User initiatedBy; @Enumerated(EnumType.STRING) @Column(nullable=false) private TransactionType type; @Column(nullable=false,precision=19,scale=2) private BigDecimal amount; @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private TransactionStatus status; @Enumerated(EnumType.STRING) @Column(name="risk_level",nullable=false) private RiskLevel riskLevel; @Column(name="risk_score",nullable=false) private int riskScore; private String description; @Column(name="created_at",nullable=false) private Instant createdAt=Instant.now(); @Column(name="confirmation_expires_at") private Instant confirmationExpiresAt;
@Column(name="resolved_at") private Instant resolvedAt;
@Column(name="outcome_reason") private String outcomeReason;
@Column(name="review_expires_at") private Instant reviewExpiresAt;
public Instant getReviewExpiresAt(){return reviewExpiresAt;}
public void awaitReview(Instant deadline){status=TransactionStatus.AWAITING_REVIEW;reviewExpiresAt=deadline;resolvedAt=null;outcomeReason="Awaiting admin review. No funds have moved or been reserved.";}
public Instant getConfirmationExpiresAt(){return confirmationExpiresAt;}
public Instant getResolvedAt(){return resolvedAt;}
public String getOutcomeReason(){return outcomeReason;}
public void awaitConfirmation(Instant expires){confirmationExpiresAt=expires;}
public boolean expired(Instant now){return status==TransactionStatus.PENDING_CONFIRMATION && confirmationExpiresAt!=null && !now.isBefore(confirmationExpiresAt) || status==TransactionStatus.AWAITING_REVIEW && reviewExpiresAt!=null && !now.isBefore(reviewExpiresAt);}
public void resolve(TransactionStatus state,String reason,Instant now){status=state;outcomeReason=reason;resolvedAt=now;}
public void reassess(int score,RiskLevel level){riskScore=score;riskLevel=level;}
protected Transaction(){} public Transaction(Account s,Account r,User u,TransactionType t,BigDecimal a,TransactionStatus st,RiskLevel rl,int rs,String d){senderAccount=s;recipientAccount=r;initiatedBy=u;type=t;amount=a;status=st;riskLevel=rl;riskScore=rs;description=d;} public Long getId(){return id;} public Account getSenderAccount(){return senderAccount;} public Account getRecipientAccount(){return recipientAccount;} public User getInitiatedBy(){return initiatedBy;} public TransactionType getType(){return type;} public BigDecimal getAmount(){return amount;} public TransactionStatus getStatus(){return status;} public RiskLevel getRiskLevel(){return riskLevel;} public int getRiskScore(){return riskScore;} public String getDescription(){return description;} public Instant getCreatedAt(){return createdAt;} }
