package com.portfolio.fraud.service;

import com.portfolio.fraud.dto.AuthDtos.RegisterRequest;
import com.portfolio.fraud.dto.PaymentRequestDtos.*;
import com.portfolio.fraud.dto.TransactionDtos.*;
import com.portfolio.fraud.entity.*;
import com.portfolio.fraud.exception.ApiException;
import com.portfolio.fraud.repository.UserRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Runs migrations and Hibernate validation against a dedicated MySQL database, never fraud_db. */
@EnabledIfEnvironmentVariable(named="PAYMENT_TEST_DB_URL",matches="jdbc:mysql://[^/]+/ledgerguard_payment_test(?:[?].*)?")
@SpringBootTest(properties={
    "spring.datasource.url=${PAYMENT_TEST_DB_URL}","spring.datasource.username=${PAYMENT_TEST_DB_USER}",
    "spring.datasource.password=${PAYMENT_TEST_DB_PASSWORD:}",
    "app.jwt.secret=payment-request-local-test-secret-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
})
@AutoConfigureMockMvc
class PaymentRequestMysqlTest {
    @Autowired PaymentRequestService requests;@Autowired IdempotencyService keys;
    @Autowired TransactionService transfers;@Autowired AccountService accounts;@Autowired AuthService auth;
    @Autowired UserRepository users;@Autowired ConfirmationService confirmations;@Autowired ReviewService reviews;
    @Autowired PendingExpiry pendingExpiry;@Autowired PaymentRequestExpiry requestExpiry;
    @Autowired JdbcTemplate db;@Autowired PlatformTransactionManager manager;@Autowired MockMvc mvc;
    record Wallet(User user,Long account,String token){}
    Wallet wallet(){
        String email="request-"+UUID.randomUUID()+"@example.test";
        var result=auth.register(new RegisterRequest(email,"Local-test-password-123","Request test"));
        var user=users.findByEmail(email).orElseThrow();
        var account=accounts.create(user,new CreateAccountRequest("CHECKING","INR",new BigDecimal("30000.00"))).id();
        return new Wallet(user,account,result.token());
    }
    View request(Wallet receiver,Wallet payer,String amount){return requests.create(receiver.user(),new Create(payer.user().getEmail(),receiver.account(),new BigDecimal(amount),"Payment test",Instant.now().plusSeconds(600)));}
    TransactionResponse pay(Wallet payer,Long id,String key){var input=new Pay(payer.account());return keys.execute(payer.user(),"payment-request-"+id,key,input,()->requests.pay(payer.user(),id,input));}
    TransactionResponse pay(Wallet payer,Long id){return pay(payer,id,UUID.randomUUID().toString());}
    BigDecimal balance(Wallet wallet){return db.queryForObject("SELECT balance FROM accounts WHERE id=?",BigDecimal.class,wallet.account());}
    long attempts(Long id){return db.queryForObject("SELECT COUNT(*) FROM payment_request_attempts WHERE request_id=?",Long.class,id);}
    void concurrent(Runnable action)throws Exception{
        try(var pool=Executors.newFixedThreadPool(6)){
            var ready=new CountDownLatch(6);var start=new CountDownLatch(1);List<Future<?>> futures=new ArrayList<>();
            for(int i=0;i<6;i++)futures.add(pool.submit(()->{ready.countDown();try{start.await();action.run();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();for(var future:futures)future.get(30,TimeUnit.SECONDS);
        }
    }
    @Test void concurrentSameKeyReplaysOneTransferAndLedger()throws Exception{
        var a=wallet();var b=wallet();var r=request(a,b,"12.34");String key=UUID.randomUUID().toString();
        var ids=ConcurrentHashMap.<Long>newKeySet();concurrent(()->ids.add(pay(b,r.id(),key).id()));
        assertThat(ids).hasSize(1);assertThat(attempts(r.id())).isEqualTo(1);
        assertThat(balance(a)).isEqualByComparingTo("30012.34");assertThat(balance(b)).isEqualByComparingTo("29987.66");
        assertThat(requests.detail(a.user(),r.id()).request().status()).isEqualTo(PaymentRequestStatus.PAID);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ledger_movements WHERE transaction_id=?",Long.class,ids.iterator().next())).isEqualTo(2);
    }
    @Test void differentConcurrentKeysCannotPayTwice()throws Exception{
        var a=wallet();var b=wallet();var r=request(a,b,"12.34");var success=new AtomicInteger();
        concurrent(()->{try{pay(b,r.id());success.incrementAndGet();}catch(ApiException e){assertThat(e.code).isEqualTo("REQUEST_NOT_PAYABLE");}});
        assertThat(success.get()).isEqualTo(1);assertThat(attempts(r.id())).isEqualTo(1);
        assertThat(balance(b)).isEqualByComparingTo("29987.66");
    }
    @Test void pendingConfirmationCompletesAndSynchronizesWithoutPolling(){
        var a=wallet();var b=wallet();var r=request(a,b,"10000.00");var tx=pay(b,r.id());
        assertThat(tx.status()).isEqualTo("PENDING_CONFIRMATION");assertThat(balance(b)).isEqualByComparingTo("30000");
        assertThat(requests.detail(a.user(),r.id()).request().status()).isEqualTo(PaymentRequestStatus.PAYMENT_PENDING);
        confirmations.confirm(b.user(),tx.id(),"Local-test-password-123");
        assertThat(requests.detail(a.user(),r.id()).request().status()).isEqualTo(PaymentRequestStatus.PAID);
        assertThat(balance(b)).isEqualByComparingTo("20000");
    }
    @Test void cancelledAttemptReopensAndHistorySurvivesRetry(){
        var a=wallet();var b=wallet();var r=request(a,b,"10000.00");var tx=pay(b,r.id());
        confirmations.cancel(b.user(),tx.id());
        assertThat(requests.detail(a.user(),r.id()).request().status()).isEqualTo(PaymentRequestStatus.PENDING);
        var next=pay(b,r.id());assertThat(next.id()).isNotEqualTo(tx.id());assertThat(attempts(r.id())).isEqualTo(2);
        assertThat(requests.detail(a.user(),r.id()).attempts()).extracting(Attempt::status).contains("CANCELLED","PENDING_CONFIRMATION");
    }
    @Test void criticalConfirmationIsBlockedAndDoesNotPayRequest(){
        var a=wallet();var b=wallet();var r=request(a,b,"10000");var tx=pay(b,r.id());
        for(int i=0;i<3;i++)transfers.deposit(b.user(),new MoneyRequest(b.account(),BigDecimal.ONE,"Risk activity"));
        assertThat(confirmations.confirm(b.user(),tx.id(),"Local-test-password-123").status()).isEqualTo("BLOCKED");
        assertThat(requests.detail(a.user(),r.id()).request().status()).isEqualTo(PaymentRequestStatus.PENDING);
        assertThat(balance(a)).isEqualByComparingTo("30000");assertThat(balance(b)).isEqualByComparingTo("30003");
    }
    @Test void incompatibleCurrencyAndInsufficientFundsDoNotCreateAttempts(){
        var a=wallet();var b=wallet();var r=request(a,b,"12.34");
        var eur=accounts.create(b.user(),new CreateAccountRequest("CHECKING","EUR",BigDecimal.TEN)).id();
        var input=new Pay(eur);
        assertThatThrownBy(()->keys.execute(b.user(),"payment-request-"+r.id(),UUID.randomUUID().toString(),input,()->requests.pay(b.user(),r.id(),input))).hasMessageContaining("same currency");
        var empty=accounts.create(b.user(),new CreateAccountRequest("CHECKING","INR",BigDecimal.ZERO)).id();
        var noFunds=new Pay(empty);
        assertThatThrownBy(()->keys.execute(b.user(),"payment-request-"+r.id(),UUID.randomUUID().toString(),noFunds,()->requests.pay(b.user(),r.id(),noFunds))).hasMessageContaining("Insufficient balance");
        assertThat(attempts(r.id())).isZero();assertThat(requests.detail(a.user(),r.id()).request().status()).isEqualTo(PaymentRequestStatus.PENDING);
    }
    @Test void concurrentDeclineAndPaymentProduceOneDecision()throws Exception{
        var a=wallet();var b=wallet();var r=request(a,b,"12.34");var count=new AtomicInteger();
        concurrent(()->{try{if(count.getAndIncrement()%2==0)pay(b,r.id());else requests.decide(b.user(),r.id(),false);}catch(ApiException e){assertThat(e.code).isEqualTo("REQUEST_NOT_PAYABLE");}});
        var state=requests.detail(a.user(),r.id()).request().status();
        assertThat(state).isIn(PaymentRequestStatus.PAID,PaymentRequestStatus.DECLINED);
        assertThat(attempts(r.id())).isEqualTo(state==PaymentRequestStatus.PAID?1:0);
        assertThat(balance(b)).isEqualByComparingTo(state==PaymentRequestStatus.PAID?"29987.66":"30000");
    }
    @Test void failedConfirmationReopensWithoutFinancialEffect(){
        var a=wallet();var b=wallet();var r=request(a,b,"10000.00");var tx=pay(b,r.id());
        transfers.withdrawal(b.user(),new MoneyRequest(b.account(),new BigDecimal("25000.00"),"Other payment"));
        // Complete this other pending withdrawal through its normal authorization path.
        var withdrawal=transfers.history(b.user(),TransactionType.WITHDRAWAL,null,null,null,null,0,20).getContent().getFirst();
        confirmations.confirm(b.user(),withdrawal.id(),"Local-test-password-123");
        assertThat(confirmations.confirm(b.user(),tx.id(),"Local-test-password-123").status()).isEqualTo("FAILED");
        assertThat(requests.detail(a.user(),r.id()).request().status()).isEqualTo(PaymentRequestStatus.PENDING);
        assertThat(balance(a)).isEqualByComparingTo("30000");
    }
    void primeCritical(Wallet payer){for(int i=0;i<3;i++)transfers.withdrawal(payer.user(),new MoneyRequest(payer.account(),BigDecimal.ONE,"Prime risk"));}
    User admin(){var wallet=wallet();db.update("UPDATE users SET role='ADMIN' WHERE id=?",wallet.user().getId());return users.findById(wallet.user().getId()).orElseThrow();}
    @Test void criticalReviewApprovalAndRejectionSynchronize(){
        var a=wallet();var b=wallet();primeCritical(b);var r=request(a,b,"29000");var tx=pay(b,r.id());
        assertThat(tx.status()).isEqualTo("AWAITING_REVIEW");
        assertThat(requests.detail(a.user(),r.id()).request().status()).isEqualTo(PaymentRequestStatus.PAYMENT_PENDING);
        reviews.decide(admin(),tx.id(),false,"Local rejection test");
        assertThat(requests.detail(a.user(),r.id()).request().status()).isEqualTo(PaymentRequestStatus.PENDING);
        var next=pay(b,r.id());reviews.decide(admin(),next.id(),true,"Local override test");
        assertThat(requests.detail(a.user(),r.id()).request().status()).isEqualTo(PaymentRequestStatus.PAID);
        assertThat(balance(a)).isEqualByComparingTo("59000");assertThat(balance(b)).isEqualByComparingTo("997");
    }
    @Test void expiryWorkerAndDeadlineFilteringArePersistent(){
        var a=wallet();var b=wallet();var r=request(a,b,"10");
        db.update("UPDATE payment_requests SET expires_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(10)),r.id());
        assertThat(requests.list(a.user(),"sent",PaymentRequestStatus.EXPIRED,0,20).getContent()).extracting(View::id).contains(r.id());
        assertThat(requests.list(a.user(),"sent",PaymentRequestStatus.PENDING,0,20).getContent()).isEmpty();
        requestExpiry.expire();requestExpiry.expire();
        assertThat(db.queryForObject("SELECT status FROM payment_requests WHERE id=?",String.class,r.id())).isEqualTo("EXPIRED");
        assertThatThrownBy(()->pay(b,r.id())).isInstanceOf(ApiException.class);assertThat(attempts(r.id())).isZero();
    }
    @Test void pendingExpiryReopensOrExpiresAccordingToRequestDeadline(){
        var a=wallet();var b=wallet();var r=request(a,b,"10000");var tx=pay(b,r.id());
        db.update("UPDATE transactions SET confirmation_expires_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(10)),tx.id());
        pendingExpiry.expire();assertThat(requests.detail(a.user(),r.id()).request().status()).isEqualTo(PaymentRequestStatus.PENDING);
        var next=pay(b,r.id());
        db.update("UPDATE payment_requests SET expires_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(10)),r.id());
        db.update("UPDATE transactions SET confirmation_expires_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(10)),next.id());
        pendingExpiry.expire();assertThat(requests.detail(a.user(),r.id()).request().status()).isEqualTo(PaymentRequestStatus.EXPIRED);
        assertThat(balance(b)).isEqualByComparingTo("30000");
    }
    @Test void rollbackDoesNotLeavePaidRequestOrLedger(){
        var a=wallet();var b=wallet();var r=request(a,b,"12.34");String key=UUID.randomUUID().toString();
        new TransactionTemplate(manager).executeWithoutResult(status->{pay(b,r.id(),key);status.setRollbackOnly();});
        assertThat(requests.detail(a.user(),r.id()).request().status()).isEqualTo(PaymentRequestStatus.PENDING);
        assertThat(attempts(r.id())).isZero();assertThat(balance(b)).isEqualByComparingTo("30000");
        pay(b,r.id(),key);assertThat(attempts(r.id())).isEqualTo(1);
    }
    @Test void apiAuthorizationIdempotencyAndNotificationsRemainPrivate()throws Exception{
        var a=wallet();var b=wallet();var other=wallet();var r=request(a,b,"12.34");
        mvc.perform(get("/api/payment-requests")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/payment-requests/"+r.id()).header("Authorization","Bearer "+other.token())).andExpect(status().isNotFound());
        mvc.perform(post("/api/payment-requests/"+r.id()+"/pay").header("Authorization","Bearer "+b.token()).contentType("application/json").content("{\"senderAccountId\":"+b.account()+"}")).andExpect(status().isBadRequest());
        String key=UUID.randomUUID().toString();
        var input=new Pay(other.account());
        assertThatThrownBy(()->keys.execute(b.user(),"payment-request-"+r.id(),key,input,()->requests.pay(b.user(),r.id(),input))).hasMessageContaining("cannot use this account");
        assertThatThrownBy(()->pay(b,r.id(),key)).hasMessageContaining("different request content");
        assertThat(balance(b)).isEqualByComparingTo("30000");
        Long notification=db.queryForObject("SELECT id FROM notifications WHERE user_id=? AND resource_type='PAYMENT_REQUEST' AND resource_id=?",Long.class,b.user().getId(),r.id());
        mvc.perform(post("/api/notifications/"+notification+"/read").header("Authorization","Bearer "+other.token())).andExpect(status().isNotFound());
        mvc.perform(get("/api/notifications").header("Authorization","Bearer "+b.token())).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].resourceType").value("PAYMENT_REQUEST"));
    }
    @Test void apiContractsMatchFormsAndDecimalSerialization()throws Exception{
        var a=wallet();var b=wallet();var r=request(a,b,"12.34");
        mvc.perform(get("/api/payment-requests?direction=received&status=&page=0").header("Authorization","Bearer "+b.token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(r.id()))
            .andExpect(jsonPath("$.content[0].amount").value("12.34")).andExpect(jsonPath("$.content[0].canPay").value(true));
        mvc.perform(get("/api/payment-requests/"+r.id()).header("Authorization","Bearer "+a.token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.request.canCancel").value(true)).andExpect(jsonPath("$.attempts").isEmpty());
        mvc.perform(post("/api/payment-requests").header("Authorization","Bearer "+a.token()).contentType("application/json")
            .content("{\"recipientEmail\":\""+b.user().getEmail()+"\",\"receivingAccountId\":"+a.account()+",\"amount\":\"9.99\",\"description\":\"Dinner\",\"expiresAt\":null}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.amount").value("9.99"));
        mvc.perform(post("/api/payment-requests").header("Authorization","Bearer "+a.token()).contentType("application/json")
            .content("{\"recipientEmail\":\"invalid\",\"receivingAccountId\":1,\"amount\":\"-1\",\"description\":\"\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/payment-requests/"+r.id()+"/pay").header("Authorization","Bearer "+b.token())
            .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"senderAccountId\":"+b.account()+"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(get("/api/payment-requests/"+r.id()).header("Authorization","Bearer "+a.token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.request.status").value("PAID")).andExpect(jsonPath("$.attempts[0].status").value("COMPLETED"));
    }
}
