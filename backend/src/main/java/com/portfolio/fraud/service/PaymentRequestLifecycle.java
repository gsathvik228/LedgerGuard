package com.portfolio.fraud.service;

import com.portfolio.fraud.entity.*;
import com.portfolio.fraud.repository.PaymentRequestRepository;
import java.time.Clock;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import static com.portfolio.fraud.service.ResourceEvents.Type.PAYMENT_REQUEST;

@Component
public class PaymentRequestLifecycle {
    private final PaymentRequestRepository requests;
    private final ResourceEvents events;
    private final Clock clock;
    public PaymentRequestLifecycle(PaymentRequestRepository requests,ResourceEvents events,Clock clock){this.requests=requests;this.events=events;this.clock=clock;}
    @EventListener @Transactional(propagation=Propagation.MANDATORY)
    public void changed(TransactionStateChanged event) {
        // Never lock the old transaction from request code. Lifecycle processing already owns that lock,
        // and the request lock serializes pay/decline/cancel against this transition without a lock cycle.
        requests.lockByTransaction(event.transactionId()).ifPresent(r->{
            if(!r.synchronize(event.status(),clock.instant()))return;
            events.audit(null,"PAYMENT_REQUEST_SYNCHRONIZED",PAYMENT_REQUEST,r.getId(),"PAYMENT_PENDING",r.getStatus().name());
            events.notify(r.getRequester().getId(),PAYMENT_REQUEST,r.getId(),"OUTCOME_"+event.transactionId(),
                "Payment request #"+r.getId()+": payment "+event.status().name().toLowerCase(java.util.Locale.ROOT)+". Request is "+r.getStatus().name().toLowerCase(java.util.Locale.ROOT)+".");
            if(r.getStatus()==PaymentRequestStatus.PENDING)
                events.notify(r.getRecipient().getId(),PAYMENT_REQUEST,r.getId(),"RETRY_"+event.transactionId(),"Your payment did not complete. This request can be paid again using a new payment attempt.");
        });
    }
}
