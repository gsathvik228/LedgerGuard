package com.portfolio.fraud.service;

import com.portfolio.fraud.dto.TransactionDtos.*;
import com.portfolio.fraud.entity.*;
import com.portfolio.fraud.exception.ApiException;
import com.portfolio.fraud.fraud.FraudDetectionService;
import com.portfolio.fraud.repository.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransactionServiceTest {
    AccountRepository accounts;
    TransactionRepository transactions;
    FraudAnalysisRepository analyses;
    FraudDetectionService fraud;
    TransactionService service;
    User alice,bob;
    Account sender,recipient;

    @BeforeEach void setup() {
        accounts=mock(AccountRepository.class); transactions=mock(TransactionRepository.class);
        analyses=mock(FraudAnalysisRepository.class); fraud=mock(FraudDetectionService.class);
        service=new TransactionService(accounts,transactions,analyses,fraud);
        alice=user(1L);bob=user(2L);
        sender=account(20L,alice,"USD");recipient=account(10L,bob,"USD");
        when(accounts.findByIdForUpdate(20L)).thenReturn(Optional.of(sender));
        when(accounts.findByIdForUpdate(10L)).thenReturn(Optional.of(recipient));
        when(transactions.save(any())).thenAnswer(invocation->invocation.getArgument(0));
        when(fraud.evaluate(any(),any(),any(),any())).thenReturn(new FraudDetectionService.Result(0,RiskLevel.LOW,List.of()));
    }
    User user(Long id) {
        User user=new User("user"+id+"@example.test","hash","User");
        ReflectionTestUtils.setField(user,"id",id);return user;
    }
    Account account(Long id,User owner,String currency) {
        Account account=new Account("LG"+id,owner,"SAVINGS",new BigDecimal("1000.00"),currency);
        ReflectionTestUtils.setField(account,"id",id);return account;
    }
    TransferRequest transfer(String amount) { return new TransferRequest(20L,10L,new BigDecimal(amount),"Test"); }

    @Test void locksInAscendingOrderAndMovesExactAmounts() {
        var result=service.transfer(alice,transfer("12.34"));
        var order=inOrder(accounts);
        order.verify(accounts).findByIdForUpdate(10L); order.verify(accounts).findByIdForUpdate(20L);
        assertThat(sender.getBalance()).isEqualByComparingTo("987.66");
        assertThat(recipient.getBalance()).isEqualByComparingTo("1012.34");
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.currency()).isEqualTo("USD");
    }
    @Test void criticalTransferPersistsAnalysisWithoutMovingFunds() {
        var finding=new FraudDetectionService.Finding("HIGH_VALUE","High value",RiskLevel.HIGH,45);
        when(fraud.evaluate(any(),any(),any(),any())).thenReturn(new FraudDetectionService.Result(90,RiskLevel.CRITICAL,List.of(finding)));
        assertThat(service.transfer(alice,transfer("900")).status()).isEqualTo("BLOCKED");
        assertThat(sender.getBalance()).isEqualByComparingTo("1000");
        assertThat(recipient.getBalance()).isEqualByComparingTo("1000");
        var capture=ArgumentCaptor.forClass(FraudAnalysis.class);
        verify(analyses).save(capture.capture());
        assertThat(capture.getValue().getAction()).isEqualTo("BLOCK");
        assertThat(capture.getValue().getRuleResults()).hasSize(1);
    }
    @Test void rejectsCrossCurrencyWithoutSavingOrChangingBalances() {
        recipient=account(10L,bob,"EUR");
        when(accounts.findByIdForUpdate(10L)).thenReturn(Optional.of(recipient));
        assertThatThrownBy(()->service.transfer(alice,transfer("10"))).isInstanceOf(ApiException.class)
            .hasMessage("Transfers require accounts in the same currency");
        assertThat(sender.getBalance()).isEqualByComparingTo("1000");
        verifyNoInteractions(transactions,analyses,fraud);
    }
    @Test void rejectsInsufficientFundsAndUnownedAccount() {
        assertThatThrownBy(()->service.transfer(alice,transfer("1001"))).hasMessage("Insufficient balance");
        assertThatThrownBy(()->service.deposit(bob,new MoneyRequest(20L,BigDecimal.ONE,""))).hasMessage("You cannot use this account");
        assertThat(sender.getBalance()).isEqualByComparingTo("1000");
        verifyNoInteractions(transactions,analyses,fraud);
    }
    @Test void rejectsInactiveAndSameAccount() {
        ReflectionTestUtils.setField(sender,"status",AccountStatus.FROZEN);
        assertThatThrownBy(()->service.withdrawal(alice,new MoneyRequest(20L,BigDecimal.ONE,""))).hasMessage("Account is inactive");
        assertThatThrownBy(()->service.transfer(alice,new TransferRequest(20L,20L,BigDecimal.ONE,""))).hasMessage("Cannot transfer to the same account");
        verifyNoInteractions(transactions,analyses,fraud);
    }
    @Test void recipientCanReadTransferButUnrelatedCustomerCannot() {
        Transaction tx=new Transaction(sender,recipient,alice,TransactionType.TRANSFER,BigDecimal.ONE,TransactionStatus.COMPLETED,RiskLevel.LOW,0,"");
        when(transactions.findById(5L)).thenReturn(Optional.of(tx));
        assertThat(service.detail(bob,5L).recipientAccountId()).isEqualTo(10L);
        assertThatThrownBy(()->service.detail(user(3L),5L)).isInstanceOf(ApiException.class).hasMessage("Transaction not found");
    }
    @Test void listsStoredSavingsType() {
        when(accounts.findByUserId(alice.getId())).thenReturn(List.of(sender));
        assertThat(service.accounts(alice).getFirst().accountType()).isEqualTo("SAVINGS");
    }
}
