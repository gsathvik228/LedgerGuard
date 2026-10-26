package com.portfolio.fraud.service;

import com.portfolio.fraud.entity.*;
import com.portfolio.fraud.repository.PaymentRequestRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentRequestLifecycleTest {
    final Instant now=Instant.parse("2026-10-26T12:00:00Z");
    PaymentRequestRepository requests;ResourceEvents events;PaymentRequestLifecycle listener;PaymentRequest request;
    @BeforeEach void setup(){
        requests=mock(PaymentRequestRepository.class);events=mock(ResourceEvents.class);
        listener=new PaymentRequestLifecycle(requests,events,Clock.fixed(now,ZoneOffset.UTC));
        var alice=new User("alice@example.test","hash","Alice");var bob=new User("bob@example.test","hash","Bob");
        ReflectionTestUtils.setField(alice,"id",1L);ReflectionTestUtils.setField(bob,"id",2L);
        request=new PaymentRequest(alice,bob,new Account("LG1",alice,"CHECKING",BigDecimal.ZERO,"INR"),BigDecimal.TEN,"Rent",now.plusSeconds(60),now);
        ReflectionTestUtils.setField(request,"id",5L);request.attach(70L,TransactionStatus.PENDING_CONFIRMATION,now);
        when(requests.lockByTransaction(70L)).thenReturn(Optional.of(request));
    }
    @ParameterizedTest @EnumSource(TransactionStatus.class)
    void laterLifecycleOutcomesDriveRequestState(TransactionStatus status) {
        listener.changed(new TransactionStateChanged(70L,status));
        var expected=switch(status){case COMPLETED->PaymentRequestStatus.PAID;case PENDING_CONFIRMATION,AWAITING_REVIEW->PaymentRequestStatus.PAYMENT_PENDING;default->PaymentRequestStatus.PENDING;};
        assertThat(request.getStatus()).isEqualTo(expected);
        assertThat(request.getResolvedAt()).isEqualTo(expected==PaymentRequestStatus.PAID?now:null);
        if(expected!=PaymentRequestStatus.PAYMENT_PENDING)verify(events).audit(isNull(),eq("PAYMENT_REQUEST_SYNCHRONIZED"),any(),eq(5L),eq("PAYMENT_PENDING"),eq(expected.name()));
        else verifyNoInteractions(events);
    }
    @Test void failureAfterDeadlineExpiresInsteadOfReopening() {
        ReflectionTestUtils.setField(request,"expiresAt",now);
        listener.changed(new TransactionStateChanged(70L,TransactionStatus.REJECTED));
        assertThat(request.getStatus()).isEqualTo(PaymentRequestStatus.EXPIRED);
    }
    @Test void completedPaymentAfterRequestDeadlineStillPays() {
        ReflectionTestUtils.setField(request,"expiresAt",now);
        listener.changed(new TransactionStateChanged(70L,TransactionStatus.COMPLETED));
        assertThat(request.getStatus()).isEqualTo(PaymentRequestStatus.PAID);
    }
    @Test void duplicateEventsCannotReopenTerminalStateOrNotifyTwice() {
        listener.changed(new TransactionStateChanged(70L,TransactionStatus.COMPLETED));
        listener.changed(new TransactionStateChanged(70L,TransactionStatus.FAILED));
        assertThat(request.getStatus()).isEqualTo(PaymentRequestStatus.PAID);
        verify(events,times(1)).audit(any(),any(),any(),any(),any(),any());
    }
}
