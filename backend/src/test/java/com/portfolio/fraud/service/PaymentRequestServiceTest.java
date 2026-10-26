package com.portfolio.fraud.service;

import com.portfolio.fraud.dto.PaymentRequestDtos.*;
import com.portfolio.fraud.dto.TransactionDtos.TransactionResponse;
import com.portfolio.fraud.entity.*;
import com.portfolio.fraud.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentRequestServiceTest {
    final Instant now=Instant.parse("2026-10-26T12:00:00Z");
    final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    PaymentRequestRepository requests;UserRepository users;AccountRepository accounts;
    TransactionService transactions;JdbcTemplate db;ResourceEvents events;PaymentRequestService service;
    User alice,bob,outsider;Account receiving;PaymentRequest request;
    @BeforeEach void setup() {
        requests=mock(PaymentRequestRepository.class);users=mock(UserRepository.class);accounts=mock(AccountRepository.class);
        transactions=mock(TransactionService.class);db=mock(JdbcTemplate.class);events=mock(ResourceEvents.class);
        service=new PaymentRequestService(requests,users,accounts,transactions,db,events,clock);
        alice=user(1L);bob=user(2L);outsider=user(3L);
        receiving=new Account("LG1",alice,"CHECKING",BigDecimal.ZERO,"INR");ReflectionTestUtils.setField(receiving,"id",10L);
        request=new PaymentRequest(alice,bob,receiving,new BigDecimal("750.00"),"Rent",now.plusSeconds(60),now);
        ReflectionTestUtils.setField(request,"id",5L);
        when(requests.lock(5L)).thenReturn(Optional.of(request));when(requests.findById(5L)).thenReturn(Optional.of(request));
        when(users.findByEmail(bob.getEmail())).thenReturn(Optional.of(bob));when(accounts.findById(10L)).thenReturn(Optional.of(receiving));
        when(requests.saveAndFlush(any())).thenAnswer(invocation->{PaymentRequest r=invocation.getArgument(0);ReflectionTestUtils.setField(r,"id",5L);return r;});
    }
    User user(Long id){var u=new User("user"+id+"@example.test","hash","User "+id);ReflectionTestUtils.setField(u,"id",id);return u;}
    Create input(String email,Long account,BigDecimal amount,Instant expiry){return new Create(email,account,amount,"Rent",expiry);}
    TransactionResponse outcome(TransactionStatus status){return new TransactionResponse(70L,"TRANSFER",request.getAmount(),status.name(),"LOW",0,"",now,20L,10L,"INR",null,null,null,false,null,false);}
    @Test void createsRegisteredCustomerRequestAndPrivateNotification() {
        var view=service.create(alice,input(" USER2@EXAMPLE.TEST ",10L,new BigDecimal("750.00"),now.plusSeconds(60)));
        assertThat(view.status()).isEqualTo(PaymentRequestStatus.PENDING);assertThat(view.currency()).isEqualTo("INR");
        assertThat(view.canCancel()).isTrue();assertThat(view.canPay()).isFalse();
        verify(events).notify(eq(2L),eq(ResourceEvents.Type.PAYMENT_REQUEST),eq(5L),eq("CREATED"),anyString());
        verifyNoInteractions(transactions);
    }
    @Test void rejectsSelfUnknownAndAdminRecipients() {
        when(users.findByEmail(alice.getEmail())).thenReturn(Optional.of(alice));
        assertThatThrownBy(()->service.create(alice,input(alice.getEmail(),10L,BigDecimal.ONE,null))).hasMessageContaining("yourself");
        assertThatThrownBy(()->service.create(alice,input("missing@example.test",10L,BigDecimal.ONE,null))).hasMessageContaining("No registered customer");
        var admin=User.developmentAdmin("admin@example.test","hash");when(users.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));
        assertThatThrownBy(()->service.create(alice,input(admin.getEmail(),10L,BigDecimal.ONE,null))).hasMessageContaining("No registered customer");
        verify(requests,never()).saveAndFlush(any());
    }
    @Test void rejectsMissingUnownedAndFrozenReceivingAccounts() {
        assertThatThrownBy(()->service.create(alice,input(bob.getEmail(),99L,BigDecimal.ONE,null))).hasMessageContaining("receiving account");
        assertThatThrownBy(()->service.create(outsider,input(bob.getEmail(),10L,BigDecimal.ONE,null))).hasMessageContaining("receiving account");
        ReflectionTestUtils.setField(receiving,"status",AccountStatus.FROZEN);
        assertThatThrownBy(()->service.create(alice,input(bob.getEmail(),10L,BigDecimal.ONE,null))).hasMessageContaining("receiving account");
    }
    @Test void rejectsInvalidAmountPrecisionAndExpiredCreation() {
        for(String amount:List.of("0","-1","1.001","100000000000000000.00"))
            assertThatThrownBy(()->service.create(alice,input(bob.getEmail(),10L,new BigDecimal(amount),null))).hasMessageContaining("positive amount");
        assertThatThrownBy(()->service.create(alice,input(bob.getEmail(),10L,BigDecimal.ONE,now))).hasMessageContaining("future");
    }
    @Test void unrelatedUsersCannotReadPayOrDecideAndRolesCannotBeSwapped() {
        assertThatThrownBy(()->service.detail(outsider,5L)).hasMessage("Payment request not found.");
        assertThatThrownBy(()->service.pay(outsider,5L,new Pay(20L))).hasMessage("Payment request not found.");
        assertThatThrownBy(()->service.pay(alice,5L,new Pay(20L))).hasMessageContaining("cannot perform");
        assertThatThrownBy(()->service.decide(alice,5L,false)).hasMessageContaining("cannot perform");
        assertThatThrownBy(()->service.decide(bob,5L,true)).hasMessageContaining("cannot perform");
        verifyNoInteractions(transactions);
    }
    @ParameterizedTest @EnumSource(TransactionStatus.class)
    void executionUsesExistingTransferAndOnlyCompletedPays(TransactionStatus status) {
        when(transactions.transfer(eq(bob),any())).thenReturn(outcome(status));
        assertThat(service.pay(bob,5L,new Pay(20L)).status()).isEqualTo(status.name());
        verify(transactions).transfer(eq(bob),argThat(t->t.senderAccountId()==20L&&t.recipientAccountId()==10L&&t.amount().compareTo(new BigDecimal("750"))==0));
        var expected=switch(status){case COMPLETED->PaymentRequestStatus.PAID;case PENDING_CONFIRMATION,AWAITING_REVIEW->PaymentRequestStatus.PAYMENT_PENDING;default->PaymentRequestStatus.PENDING;};
        assertThat(request.getStatus()).isEqualTo(expected);assertThat(request.getTransactionId()).isEqualTo(70L);
        verify(db).update("INSERT INTO payment_request_attempts(request_id,transaction_id) VALUES(?,?)",5L,70L);
    }
    @Test void differentKeyOrRepeatedPaymentCannotStartSecondPendingOrCompletedTransfer() {
        when(transactions.transfer(eq(bob),any())).thenReturn(outcome(TransactionStatus.PENDING_CONFIRMATION));
        service.pay(bob,5L,new Pay(20L));
        assertThatThrownBy(()->service.pay(bob,5L,new Pay(20L))).hasMessageContaining("no longer pending");
        assertThatThrownBy(()->service.decide(bob,5L,false)).hasMessageContaining("no longer pending");
        request.synchronize(TransactionStatus.COMPLETED,now);
        assertThatThrownBy(()->service.pay(bob,5L,new Pay(20L))).hasMessageContaining("no longer pending");
        verify(transactions,times(1)).transfer(any(),any());
    }
    @Test void expiredRequestIsPersistedAndNeverTransferred() {
        ReflectionTestUtils.setField(request,"expiresAt",now);
        assertThatThrownBy(()->service.pay(bob,5L,new Pay(20L))).hasMessageContaining("no longer pending");
        assertThat(request.getStatus()).isEqualTo(PaymentRequestStatus.EXPIRED);verifyNoInteractions(transactions);
    }
    @Test void declineAndCancelAreTerminalAndAudited() {
        assertThat(service.decide(bob,5L,false).status()).isEqualTo(PaymentRequestStatus.DECLINED);
        assertThatThrownBy(()->service.decide(alice,5L,true)).hasMessageContaining("no longer pending");
        assertThatThrownBy(()->service.pay(bob,5L,new Pay(20L))).hasMessageContaining("no longer pending");
        verify(events).audit(eq(2L),eq("PAYMENT_REQUEST_DECLINED"),any(),eq(5L),eq("PENDING"),eq("DECLINED"));
    }
    @Test void creatorCanCancel() {assertThat(service.decide(alice,5L,true).status()).isEqualTo(PaymentRequestStatus.CANCELLED);}
    @Test void invalidDirectionAndPaginationAreRejected() {
        assertThatThrownBy(()->service.list(alice,"all",null,0,20)).hasMessageContaining("Direction");
        assertThatThrownBy(()->service.list(alice,"sent",null,-1,20)).hasMessageContaining("Page");
    }
}
