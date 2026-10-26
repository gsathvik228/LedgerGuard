package com.portfolio.fraud.service;

import com.portfolio.fraud.dto.AuthDtos.*;
import com.portfolio.fraud.dto.TransactionDtos.*;
import com.portfolio.fraud.entity.*;
import com.portfolio.fraud.repository.UserRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
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

/** Opt-in real MySQL checks. The URL must name the dedicated test database. */
@EnabledIfEnvironmentVariable(named="SCHEDULE_TEST_DB_URL",matches="jdbc:mysql://[^/]+/ledgerguard_schedule_test(?:[?].*)?")
@SpringBootTest(properties={
    "spring.datasource.url=${SCHEDULE_TEST_DB_URL}",
    "spring.datasource.username=${SCHEDULE_TEST_DB_USER}",
    "spring.datasource.password=${SCHEDULE_TEST_DB_PASSWORD:}",
    "app.jwt.secret=scheduled-payment-local-test-secret-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ",
    "app.scheduled-payments.initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
class ScheduledPaymentMysqlTest {
    @Autowired ScheduledPaymentService payments;
    @Autowired TransactionService transfers;
    @Autowired AccountService accounts;
    @Autowired AuthService auth;
    @Autowired UserRepository users;
    @Autowired ConfirmationService confirmations;
    @Autowired ReviewService reviews;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    @Autowired MockMvc mvc;
    record Wallet(User user,Long account,String token){}
    Wallet wallet() {
        String email="schedule-"+UUID.randomUUID()+"@example.test";
        var result=auth.register(new RegisterRequest(email,"Local-test-password-123","Schedule test"));
        User user=users.findByEmail(email).orElseThrow();
        Long account=accounts.create(user,new CreateAccountRequest("CHECKING","USD",new BigDecimal("30000.00"))).id();
        return new Wallet(user,account,result.token());
    }
    ScheduledPaymentService.Payment schedule(Wallet a,Wallet b,String amount){
        return payments.create(a.user(),UUID.randomUUID().toString(),
            new ScheduledPaymentService.Request(a.account(),b.account(),new BigDecimal(amount),"MySQL verification",Instant.now().plusSeconds(60)));
    }
    void due(Long id){db.update("UPDATE scheduled_payments SET execute_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(60)),id);}
    BigDecimal balance(Wallet wallet){return db.queryForObject("SELECT balance FROM accounts WHERE id=?",BigDecimal.class,wallet.account());}
    void concurrently(Runnable action) throws Exception {
        try(var pool=Executors.newFixedThreadPool(6)){
            var ready=new CountDownLatch(6);var start=new CountDownLatch(1);
            List<Future<?>> futures=new ArrayList<>();
            for(int i=0;i<6;i++)futures.add(pool.submit(()->{ready.countDown();try{start.await();action.run();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            for(var future:futures)future.get(30,TimeUnit.SECONDS);
        }
    }
    @Test void concurrentWorkersExecuteExactlyOnce() throws Exception {
        var a=wallet();var b=wallet();var s=schedule(a,b,"12.34");due(s.id());
        concurrently(()->payments.execute(s.id()));
        var done=payments.detail(a.user(),s.id());
        assertThat(done.transaction().status()).isEqualTo("COMPLETED");
        assertThat(balance(a)).isEqualByComparingTo("29987.66");assertThat(balance(b)).isEqualByComparingTo("30012.34");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM transactions WHERE initiated_by_user_id=?",Long.class,a.user().getId())).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ledger_movements WHERE transaction_id=?",Long.class,done.transaction().id())).isEqualTo(2);
    }
    @Test void rollbackAndRediscoveryRecoverWithoutDuplicates() {
        var a=wallet();var b=wallet();var s=schedule(a,b,"12.34");due(s.id());
        new TransactionTemplate(manager).executeWithoutResult(status->{payments.execute(s.id());status.setRollbackOnly();});
        assertThat(payments.detail(a.user(),s.id()).status()).isEqualTo("SCHEDULED");
        assertThat(balance(a)).isEqualByComparingTo("30000");
        new ScheduledPaymentWorker(db,payments).processDue();
        Long transactionId=payments.detail(a.user(),s.id()).transaction().id();
        new ScheduledPaymentWorker(db,payments).processDue();
        assertThat(payments.detail(a.user(),s.id()).transaction().id()).isEqualTo(transactionId);
        assertThat(balance(a)).isEqualByComparingTo("29987.66");
    }
    @Test void cancellationRaceCannotMoveFundsTwice() throws Exception {
        var a=wallet();var b=wallet();var s=schedule(a,b,"12.34");due(s.id());
        var count=new java.util.concurrent.atomic.AtomicInteger();
        concurrently(()->{if(count.getAndIncrement()%2==0)payments.execute(s.id());else payments.cancel(a.user(),s.id());});
        var done=payments.detail(a.user(),s.id());
        if(done.status().equals("CANCELLED")){
            assertThat(done.transaction()).isNull();assertThat(balance(a)).isEqualByComparingTo("30000");
        }else{
            assertThat(done.transaction().status()).isEqualTo("COMPLETED");assertThat(balance(a)).isEqualByComparingTo("29987.66");
        }
    }
    @Test void concurrentCreationRetriesReturnOneSchedule() throws Exception {
        var a=wallet();var b=wallet();String key=UUID.randomUUID().toString();
        var request=new ScheduledPaymentService.Request(a.account(),b.account(),new BigDecimal("12.34"),null,Instant.now().plusSeconds(60));
        var ids=ConcurrentHashMap.<Long>newKeySet();
        concurrently(()->ids.add(payments.create(a.user(),key,request).id()));
        assertThat(ids).hasSize(1);
        assertThat(balance(a)).isEqualByComparingTo("30000");
    }
    @Test void mediumAndHighRequireExistingPasswordConfirmation() {
        for(String amount:List.of("10000.00","29000.00")){
            var a=wallet();var b=wallet();var s=schedule(a,b,amount);due(s.id());payments.execute(s.id());
            var pending=payments.detail(a.user(),s.id()).transaction();
            assertThat(pending.status()).isEqualTo("PENDING_CONFIRMATION");
            assertThat(balance(a)).isEqualByComparingTo("30000");
            assertThat(confirmations.confirm(a.user(),pending.id(),"Local-test-password-123").status()).isEqualTo("COMPLETED");
            payments.execute(s.id());
            assertThat(balance(a)).isEqualByComparingTo(new BigDecimal("30000").subtract(new BigDecimal(amount)));
        }
    }
    @Test void criticalUsesAdminReviewAndPendingCanBeCancelled() {
        var a=wallet();var b=wallet();
        for(int i=0;i<3;i++)transfers.withdrawal(a.user(),new MoneyRequest(a.account(),BigDecimal.ONE,null));
        var s=schedule(a,b,"29000.00");due(s.id());payments.execute(s.id());
        var pending=payments.detail(a.user(),s.id()).transaction();
        assertThat(pending.status()).isEqualTo("AWAITING_REVIEW");
        assertThat(balance(a)).isEqualByComparingTo("29997");
        assertThat(payments.cancel(a.user(),s.id()).transaction().status()).isEqualTo("CANCELLED");
        var second=schedule(a,b,"29000.00");due(second.id());payments.execute(second.id());
        var admin=wallet().user();
        db.update("UPDATE users SET role='ADMIN' WHERE id=?",admin.getId());
        admin=users.findById(admin.getId()).orElseThrow();
        assertThat(reviews.decide(admin,payments.detail(a.user(),second.id()).transaction().id(),true,"Local scheduled payment test").status()).isEqualTo("COMPLETED");
    }
    @Test void changedFundsAndAccountStatusAreCheckedAtExecution() {
        var a=wallet();var b=wallet();var s=schedule(a,b,"12.34");
        db.update("UPDATE accounts SET balance=0 WHERE id=?",a.account());due(s.id());payments.execute(s.id());
        assertThat(payments.detail(a.user(),s.id()).status()).isEqualTo("FAILED");
        assertThat(payments.detail(a.user(),s.id()).transaction()).isNull();
        db.update("UPDATE accounts SET balance=30000 WHERE id=?",a.account());payments.execute(s.id());
        assertThat(payments.detail(a.user(),s.id()).status()).isEqualTo("FAILED");
        var frozen=schedule(a,b,"12.34");db.update("UPDATE accounts SET status='FROZEN' WHERE id=?",a.account());
        due(frozen.id());payments.execute(frozen.id());
        assertThat(payments.detail(a.user(),frozen.id()).status()).isEqualTo("FAILED");
    }
    @Test void jwtOwnershipHistoryAndOrdinaryTransfersRemainProtected() throws Exception {
        var a=wallet();var b=wallet();var s=schedule(a,b,"12.34");
        mvc.perform(get("/api/scheduled-payments")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/scheduled-payments/"+s.id()).header("Authorization","Bearer "+b.token())).andExpect(status().isNotFound());
        mvc.perform(post("/api/scheduled-payments/"+s.id()+"/cancel").header("Authorization","Bearer "+b.token())).andExpect(status().isNotFound());
        var login=auth.login(new LoginRequest(a.user().getEmail(),"Local-test-password-123"));
        mvc.perform(get("/api/me").header("Authorization","Bearer "+login.token())).andExpect(status().isOk());
        due(s.id());payments.execute(s.id());
        mvc.perform(get("/api/transactions/"+payments.detail(a.user(),s.id()).transaction().id()).header("Authorization","Bearer "+b.token())).andExpect(status().isOk());
        var tx=transfers.transfer(a.user(),new TransferRequest(a.account(),b.account(),BigDecimal.ONE,null));
        assertThat(tx.status()).isEqualTo("COMPLETED");
    }
}
