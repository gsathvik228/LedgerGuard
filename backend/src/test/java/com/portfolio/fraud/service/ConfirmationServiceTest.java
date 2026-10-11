package com.portfolio.fraud.service;

import com.portfolio.fraud.entity.*;
import com.portfolio.fraud.dto.ConfirmationRequest;
import com.portfolio.fraud.dto.TransactionDtos.*;
import com.portfolio.fraud.exception.*;
import com.portfolio.fraud.fraud.FraudDetectionService;
import com.portfolio.fraud.repository.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfirmationServiceTest {
    TransactionRepository transactions=mock(TransactionRepository.class);
    AccountRepository accounts=mock(AccountRepository.class);
    UserRepository users=mock(UserRepository.class);
    ConfirmationGuardRepository guards=mock(ConfirmationGuardRepository.class);
    FraudAnalysisRepository analyses=mock(FraudAnalysisRepository.class);
    FraudDetectionService fraud=mock(FraudDetectionService.class);
    PasswordEncoder encoder=mock(PasswordEncoder.class);
    TransactionService views=new TransactionService(accounts,transactions,analyses,fraud,mock(LedgerJournal.class));
    ConfirmationService service=new ConfirmationService(transactions,accounts,users,guards,analyses,fraud,encoder,views,mock(LedgerJournal.class));
    User alice=new User("alice@example.test","hash","Alice");
    User bob=new User("bob@example.test","other","Bob");
    Account sender=new Account("S",alice,"CHECKING",new BigDecimal("20000"),"USD");
    Account recipient=new Account("R",bob,"SAVINGS",new BigDecimal("100"),"USD");
    Transaction tx;
    ConfirmationGuard guard;
    @BeforeEach void prepare() {
        ReflectionTestUtils.setField(alice,"id",1L);ReflectionTestUtils.setField(bob,"id",2L);
        ReflectionTestUtils.setField(sender,"id",20L);ReflectionTestUtils.setField(recipient,"id",10L);
        tx=new Transaction(sender,recipient,alice,TransactionType.TRANSFER,new BigDecimal("10000"),TransactionStatus.PENDING_CONFIRMATION,RiskLevel.MEDIUM,45,"");
        ReflectionTestUtils.setField(tx,"id",5L);tx.awaitConfirmation(Instant.now().plusSeconds(300));
        guard=new ConfirmationGuard(1L,Instant.now());
        when(transactions.findByIdForUpdate(5L)).thenReturn(Optional.of(tx));
        when(accounts.findByIdForUpdate(20L)).thenReturn(Optional.of(sender));
        when(accounts.findByIdForUpdate(10L)).thenReturn(Optional.of(recipient));
        when(users.findById(1L)).thenReturn(Optional.of(alice));
        when(guards.lock(1L)).thenReturn(guard);
        when(encoder.matches("correct","hash")).thenReturn(true);
        when(fraud.evaluate(alice,sender,recipient,tx.getAmount(),5L))
            .thenReturn(new FraudDetectionService.Result(45,RiskLevel.MEDIUM,List.of()));
    }
    @Test void mediumOutgoingWaitsWithoutDebiting() {
        when(fraud.evaluate(alice,sender,recipient,new BigDecimal("10000"))).thenReturn(new FraudDetectionService.Result(45,RiskLevel.MEDIUM,List.of()));
        when(transactions.save(any())).thenAnswer(i->i.getArgument(0));
        var result=views.transfer(alice,new TransferRequest(20L,10L,new BigDecimal("10000"),""));
        assertThat(result.status()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(result.confirmationExpiresAt()).isAfter(Instant.now());
        unchanged();
    }
    @Test void wrongPasswordIsCountedWithoutMovingFunds() {
        assertThatThrownBy(()->service.confirm(alice,5L,"incorrect")).isInstanceOf(ConfirmationDenied.class);
        assertThat(guard.getFailedAttempts()).isEqualTo(1);
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PENDING_CONFIRMATION);
        verifyNoInteractions(accounts,fraud,analyses);unchanged();
        assertThat(new ConfirmationRequest("sensitive").toString()).doesNotContain("sensitive");
    }
    @Test void failedAttemptsAreRateLimitedAcrossTheUser() {
        for(int i=0;i<5;i++) assertThatThrownBy(()->service.confirm(alice,5L,"incorrect")).isInstanceOf(ConfirmationDenied.class);
        assertThatThrownBy(()->service.confirm(alice,5L,"correct")).isInstanceOfSatisfying(ConfirmationDenied.class,
            e->assertThat(e.status.value()).isEqualTo(429));
        verify(encoder,never()).matches("correct","hash");unchanged();
    }
    @Test void recipientAndAdminCannotAuthorizeOrCancel() {
        User admin=User.developmentAdmin("admin@example.test","hash");
        ReflectionTestUtils.setField(admin,"id",3L);
        for(User actor:List.of(bob,admin)) {
            assertThatThrownBy(()->service.confirm(actor,5L,"correct")).isInstanceOf(ApiException.class);
            assertThatThrownBy(()->service.cancel(actor,5L)).isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(guards,encoder,accounts);unchanged();
    }
    @Test void expiredTransactionCannotExecute() {
        tx.awaitConfirmation(Instant.now().minusSeconds(1));
        assertThat(service.confirm(alice,5L,"correct").status()).isEqualTo("EXPIRED");
        assertThat(tx.getResolvedAt()).isNotNull();
        verifyNoInteractions(guards,encoder,accounts,fraud);unchanged();
    }
    @Test void expiresEvenWhenPasswordCheckTakesPastDeadline() {
        when(encoder.matches("correct","hash")).thenAnswer(i->{tx.awaitConfirmation(Instant.now().minusSeconds(1));return true;});
        assertThat(service.confirm(alice,5L,"correct").status()).isEqualTo("EXPIRED");
        verifyNoInteractions(fraud,analyses);unchanged();
    }
    @Test void cancellationIsFinalAndDoesNotRequirePassword() {
        assertThat(service.cancel(alice,5L).status()).isEqualTo("CANCELLED");
        assertThat(service.confirm(alice,5L,"correct").status()).isEqualTo("CANCELLED");
        verifyNoInteractions(guards,encoder,accounts,fraud);unchanged();
    }
    @Test void duplicateConfirmationMovesFundsExactlyOnceAndLocksInOrder() {
        assertThat(service.confirm(alice,5L,"correct").status()).isEqualTo("COMPLETED");
        assertThat(service.confirm(alice,5L,"correct").status()).isEqualTo("COMPLETED");
        var locks=inOrder(transactions,accounts);
        locks.verify(transactions).findByIdForUpdate(5L);
        locks.verify(accounts).findByIdForUpdate(10L);
        locks.verify(accounts).findByIdForUpdate(20L);
        assertThat(sender.getBalance()).isEqualByComparingTo("10000");
        assertThat(recipient.getBalance()).isEqualByComparingTo("10100");
        verify(fraud,times(1)).evaluate(alice,sender,recipient,tx.getAmount(),5L);
        verify(analyses,times(1)).save(any());
    }
    @Test void insufficientFundsRejectsAtConfirmation() {
        sender.debit(new BigDecimal("15000"));
        var result=service.confirm(alice,5L,"correct");
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.outcomeReason()).contains("Insufficient funds");
        assertThat(sender.getBalance()).isEqualByComparingTo("5000");
        assertThat(recipient.getBalance()).isEqualByComparingTo("100");
        verify(analyses).save(any());
    }
    @Test void criticalReassessmentBlocksWithoutMovingFunds() {
        when(fraud.evaluate(alice,sender,recipient,tx.getAmount(),5L)).thenReturn(new FraudDetectionService.Result(90,RiskLevel.CRITICAL,List.of()));
        assertThat(service.confirm(alice,5L,"correct").status()).isEqualTo("BLOCKED");
        assertThat(tx.getRiskScore()).isEqualTo(90);unchanged();
    }
    @Test void existingCriticalBlockCannotBeOverridden() {
        tx.resolve(TransactionStatus.BLOCKED,"Original critical assessment",Instant.now());
        assertThat(service.confirm(alice,5L,"correct").status()).isEqualTo("BLOCKED");
        verifyNoInteractions(guards,encoder,accounts,fraud);unchanged();
    }
    @Test void inactiveOrCrossCurrencyAccountsFailRevalidation() {
        ReflectionTestUtils.setField(recipient,"status",AccountStatus.FROZEN);
        assertThat(service.confirm(alice,5L,"correct").status()).isEqualTo("FAILED");unchanged();
        ReflectionTestUtils.setField(recipient,"status",AccountStatus.ACTIVE);
        ReflectionTestUtils.setField(recipient,"currency","EUR");
        ReflectionTestUtils.setField(tx,"status",TransactionStatus.PENDING_CONFIRMATION);
        assertThat(service.confirm(alice,5L,"correct").status()).isEqualTo("FAILED");unchanged();
    }
    void unchanged() {
        assertThat(sender.getBalance()).isEqualByComparingTo("20000");
        assertThat(recipient.getBalance()).isEqualByComparingTo("100");
    }
}
