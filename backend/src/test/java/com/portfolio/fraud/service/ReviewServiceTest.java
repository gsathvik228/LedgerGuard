package com.portfolio.fraud.service;

import com.portfolio.fraud.entity.*;
import com.portfolio.fraud.fraud.FraudDetectionService;
import com.portfolio.fraud.repository.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewServiceTest {
    TransactionRepository transactions=mock(TransactionRepository.class);
    AccountRepository accounts=mock(AccountRepository.class);
    FraudAnalysisRepository analyses=mock(FraudAnalysisRepository.class);
    FraudDetectionService fraud=mock(FraudDetectionService.class);
    LedgerJournal journal=mock(LedgerJournal.class);
    TransactionService views=new TransactionService(accounts,transactions,analyses,fraud,journal);
    ReviewService service=new ReviewService(transactions,accounts,analyses,fraud,views,journal);
    User owner=new User("owner@example.test","hash","Owner"),other=new User("other@example.test","hash","Other"),admin=User.developmentAdmin("admin@example.test","hash");
    Account sender=new Account("S",owner,"CHECKING",new BigDecimal("20000")),recipient=new Account("R",other,"SAVINGS",BigDecimal.ZERO);
    Transaction tx;
    @BeforeEach void setup(){ReflectionTestUtils.setField(owner,"id",1L);ReflectionTestUtils.setField(other,"id",2L);ReflectionTestUtils.setField(admin,"id",3L);ReflectionTestUtils.setField(sender,"id",20L);ReflectionTestUtils.setField(recipient,"id",10L);tx=new Transaction(sender,recipient,owner,TransactionType.TRANSFER,new BigDecimal("10000"),TransactionStatus.AWAITING_REVIEW,RiskLevel.CRITICAL,90,"");ReflectionTestUtils.setField(tx,"id",5L);tx.awaitReview(Instant.now().plusSeconds(60));when(transactions.findByIdForUpdate(5L)).thenReturn(Optional.of(tx));when(accounts.findByIdForUpdate(20L)).thenReturn(Optional.of(sender));when(accounts.findByIdForUpdate(10L)).thenReturn(Optional.of(recipient));when(fraud.evaluate(owner,sender,recipient,tx.getAmount(),5L)).thenReturn(new FraudDetectionService.Result(90,RiskLevel.CRITICAL,List.of()));}
    @Test void approvalIsExplicitAndLocksInDeterministicOrder(){assertThat(service.decide(admin,5L,true,"Customer intent verified").status()).isEqualTo("COMPLETED");var order=inOrder(transactions,accounts);order.verify(transactions).findByIdForUpdate(5L);order.verify(accounts).findByIdForUpdate(10L);order.verify(accounts).findByIdForUpdate(20L);assertThat(sender.getBalance()).isEqualByComparingTo("10000");assertThat(recipient.getBalance()).isEqualByComparingTo("10000");verify(analyses).save(argThat(a->a.getAction().equals("ADMIN_OVERRIDE")&&a.getRiskLevel()==RiskLevel.CRITICAL));verify(journal).changed(eq(3L),eq("ADMIN_APPROVED"),same(tx),eq("AWAITING_REVIEW"),anyString());assertThatThrownBy(()->service.decide(admin,5L,true,"Retry with another key")).hasMessageContaining("no longer awaiting");assertThat(sender.getBalance()).isEqualByComparingTo("10000");}
    @Test void customerAndMissingReasonAreDenied(){assertThatThrownBy(()->service.decide(owner,5L,true,"Reason")).hasMessageContaining("Administrator");assertThatThrownBy(()->service.decide(admin,5L,true," ")).hasMessageContaining("reason");verifyNoInteractions(transactions,accounts,analyses,journal);}
    @Test void expiredAndRejectedRequestsNeverMoveFunds(){tx.awaitReview(Instant.now().minusSeconds(1));assertThat(service.decide(admin,5L,true,"Reason").status()).isEqualTo("EXPIRED");verifyNoInteractions(accounts,fraud,analyses);assertThat(sender.getBalance()).isEqualByComparingTo("20000");}
    @Test void rejectionRecordsReasonWithoutFraudOverride(){assertThat(service.decide(admin,5L,false,"Unable to verify intent").status()).isEqualTo("REJECTED");assertThat(tx.getOutcomeReason()).contains("Unable to verify intent");verifyNoInteractions(accounts,fraud,analyses);verify(journal).changed(eq(3L),eq("ADMIN_REJECTED"),same(tx),eq("AWAITING_REVIEW"),anyString());}
    @Test void insufficientFundsFailEvenWithAdminApproval(){sender.debit(new BigDecimal("15000"));assertThat(service.decide(admin,5L,true,"Reason").status()).isEqualTo("FAILED");assertThat(sender.getBalance()).isEqualByComparingTo("5000");assertThat(recipient.getBalance()).isEqualByComparingTo("0");verify(analyses).save(argThat(a->a.getAction().equals("REJECT")));}
    @Test void closedStatesCannotExecute(){for(var state:List.of(TransactionStatus.BLOCKED,TransactionStatus.CANCELLED,TransactionStatus.REJECTED,TransactionStatus.EXPIRED,TransactionStatus.COMPLETED,TransactionStatus.PENDING_CONFIRMATION)){tx.resolve(state,"Existing record",Instant.now());assertThatThrownBy(()->service.decide(admin,5L,true,"Reason")).hasMessageContaining("no longer awaiting");}verifyNoInteractions(accounts,fraud,analyses);}
    @Test void activeAccountsCurrencyAndOwnershipAreRechecked(){ReflectionTestUtils.setField(recipient,"currency","EUR");assertThat(service.decide(admin,5L,true,"Reason").status()).isEqualTo("FAILED");assertThat(sender.getBalance()).isEqualByComparingTo("20000");assertThat(recipient.getBalance()).isEqualByComparingTo("0");}
    @Test void expiryAfterWaitingForAccountLocksCannotExecute(){when(accounts.findByIdForUpdate(20L)).thenAnswer(i->{tx.awaitReview(Instant.now().minusSeconds(1));return Optional.of(sender);});assertThat(service.decide(admin,5L,true,"Reason").status()).isEqualTo("EXPIRED");verifyNoInteractions(fraud,analyses);assertThat(sender.getBalance()).isEqualByComparingTo("20000");}
}
