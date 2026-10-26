package com.portfolio.fraud.service;

import com.portfolio.fraud.repository.PaymentRequestRepository;
import java.time.Clock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class PaymentRequestExpiry {
    private final PaymentRequestRepository requests;private final PaymentRequestService service;
    private final Clock clock;private final TransactionTemplate unit;
    public PaymentRequestExpiry(PaymentRequestRepository requests,PaymentRequestService service,Clock clock,PlatformTransactionManager manager){this.requests=requests;this.service=service;this.clock=clock;unit=new TransactionTemplate(manager);}
    @Scheduled(fixedDelayString="${app.payment-requests.expiry-delay-ms:5000}")
    public void expire(){for(Long id:requests.expired(clock.instant(),PageRequest.of(0,100)))unit.executeWithoutResult(s->requests.lock(id).ifPresent(service::expire));}
}
