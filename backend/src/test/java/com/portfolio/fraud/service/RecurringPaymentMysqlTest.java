package com.portfolio.fraud.service;

import com.portfolio.fraud.dto.AuthDtos.RegisterRequest;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.portfolio.fraud.service.Recurrence.Frequency.*;

@EnabledIfEnvironmentVariable(named="SCHEDULE_TEST_DB_URL",matches="jdbc:mysql://[^/]+/ledgerguard_schedule_test(?:[?].*)?")
@SpringBootTest(properties={"spring.datasource.url=${SCHEDULE_TEST_DB_URL}","spring.datasource.username=${SCHEDULE_TEST_DB_USER}","spring.datasource.password=${SCHEDULE_TEST_DB_PASSWORD:}",
    "app.jwt.secret=recurring-payment-local-test-secret-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ",
    "app.scheduled-payments.initial-delay-ms=3600000","app.recurring-payments.initial-delay-ms=3600000"})
@AutoConfigureMockMvc
class RecurringPaymentMysqlTest {
    @Autowired RecurringPaymentService plans;@Autowired ScheduledPaymentService schedules;
    @Autowired TransactionService transfers;@Autowired AccountService accounts;@Autowired AuthService auth;
    @Autowired UserRepository users;@Autowired ConfirmationService confirmations;@Autowired ReviewService reviews;
    @Autowired JdbcTemplate db;@Autowired PlatformTransactionManager manager;@Autowired MockMvc mvc;
    record Wallet(User user,Long account,String token){}
    Wallet wallet(){
        String email="recurring-"+UUID.randomUUID()+"@example.test";var result=auth.register(new RegisterRequest(email,"Local-test-password-123","Recurring test"));
        var user=users.findByEmail(email).orElseThrow();var account=accounts.create(user,new CreateAccountRequest("CHECKING","USD",new BigDecimal("30000.00"))).id();
        return new Wallet(user,account,result.token());
    }
    RecurringPaymentService.Request input(Wallet a,Wallet b,String amount){return new RecurringPaymentService.Request(a.account(),b.account(),new BigDecimal(amount),"Recurring test",Instant.now().plusSeconds(60),DAILY,"Asia/Kolkata",3);}
    RecurringPaymentService.Plan plan(Wallet a,Wallet b,String amount){return plans.create(a.user(),UUID.randomUUID().toString(),input(a,b,amount));}
    long count(Long id){return db.queryForObject("SELECT COUNT(*) FROM scheduled_payments WHERE recurring_plan_id=?",Long.class,id);}
    BigDecimal balance(Wallet w){return db.queryForObject("SELECT balance FROM accounts WHERE id=?",BigDecimal.class,w.account());}
    void due(Long id){db.update("UPDATE scheduled_payments SET execute_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(60)),id);}
    void concurrent(Runnable action)throws Exception{
        try(var pool=Executors.newFixedThreadPool(6)){var ready=new CountDownLatch(6);var start=new CountDownLatch(1);List<Future<?>> futures=new ArrayList<>();
            for(int i=0;i<6;i++)futures.add(pool.submit(()->{ready.countDown();try{start.await();action.run();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();for(var f:futures)f.get(30,TimeUnit.SECONDS);
        }
    }
    @Test void concurrentCreationAndRepeatedGenerationQueueOneFiniteSeries()throws Exception{
        var a=wallet();var b=wallet();var input=input(a,b,"12.34");String key=UUID.randomUUID().toString();var ids=ConcurrentHashMap.<Long>newKeySet();
        concurrent(()->ids.add(plans.create(a.user(),key,input).id()));assertThat(ids).hasSize(1);Long id=ids.iterator().next();
        concurrent(()->plans.generate(id));assertThat(count(id)).isEqualTo(3);assertThat(plans.detail(a.user(),id).generatedCount()).isEqualTo(3);
        assertThat(balance(a)).isEqualByComparingTo("30000");
        assertThatThrownBy(()->plans.create(a.user(),key,input(a,b,"99.99"))).hasMessageContaining("different recurring plan");
    }
    @Test void concurrentOccurrenceExecutionUsesTheExistingLedgerExactlyOnce()throws Exception{
        var a=wallet();var b=wallet();var p=plan(a,b,"12.34");var occurrence=plans.occurrences(a.user(),p.id(),0,20).getFirst();due(occurrence.id());
        concurrent(()->schedules.execute(occurrence.id()));var result=schedules.detail(a.user(),occurrence.id());
        assertThat(result.transaction().status()).isEqualTo("COMPLETED");assertThat(result.recurringPlanId()).isEqualTo(p.id());
        assertThat(balance(a)).isEqualByComparingTo("29987.66");assertThat(balance(b)).isEqualByComparingTo("30012.34");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ledger_movements WHERE transaction_id=?",Long.class,result.transaction().id())).isEqualTo(2);
    }
    @Test void pendingConfirmationAndCancellationKeepAuthoritativeTransactionOutcomes(){
        var a=wallet();var b=wallet();var p=plan(a,b,"10000");var occurrence=plans.occurrences(a.user(),p.id(),0,20).getFirst();due(occurrence.id());schedules.execute(occurrence.id());
        var tx=schedules.detail(a.user(),occurrence.id()).transaction();assertThat(tx.status()).isEqualTo("PENDING_CONFIRMATION");assertThat(balance(a)).isEqualByComparingTo("30000");
        plans.cancel(a.user(),p.id());plans.generate(p.id());assertThat(count(p.id())).isEqualTo(3);
        assertThat(plans.occurrences(a.user(),p.id(),0,20)).extracting(ScheduledPaymentService.Payment::status).containsExactly("PROCESSED","CANCELLED","CANCELLED");
        confirmations.confirm(a.user(),tx.id(),"Local-test-password-123");
        assertThat(plans.occurrences(a.user(),p.id(),0,20).getFirst().transaction().status()).isEqualTo("COMPLETED");assertThat(balance(a)).isEqualByComparingTo("20000");
    }
    @Test void criticalOccurrenceUsesAdminReviewWithoutBypassingFraud(){
        var a=wallet();var b=wallet();for(int i=0;i<3;i++)transfers.withdrawal(a.user(),new MoneyRequest(a.account(),BigDecimal.ONE,"Risk test"));
        var p=plan(a,b,"29000");var first=plans.occurrences(a.user(),p.id(),0,20).getFirst();due(first.id());schedules.execute(first.id());
        var tx=schedules.detail(a.user(),first.id()).transaction();assertThat(tx.status()).isEqualTo("AWAITING_REVIEW");assertThat(balance(a)).isEqualByComparingTo("29997");
        var admin=wallet();db.update("UPDATE users SET role='ADMIN' WHERE id=?",admin.user().getId());
        reviews.decide(users.findById(admin.user().getId()).orElseThrow(),tx.id(),false,"Local recurring test");
        assertThat(plans.occurrences(a.user(),p.id(),0,20).getFirst().transaction().status()).isEqualTo("REJECTED");assertThat(balance(a)).isEqualByComparingTo("29997");
    }
    @Test void insufficientFundsFailOnlyThatOccurrenceAndNeverRetryItsDebit(){
        var a=wallet();var b=wallet();var p=plan(a,b,"12.34");var first=plans.occurrences(a.user(),p.id(),0,20).getFirst();
        db.update("UPDATE accounts SET balance=0 WHERE id=?",a.account());due(first.id());schedules.execute(first.id());schedules.execute(first.id());
        assertThat(schedules.detail(a.user(),first.id()).status()).isEqualTo("FAILED");assertThat(schedules.detail(a.user(),first.id()).transaction()).isNull();
        assertThat(balance(b)).isEqualByComparingTo("30000");assertThat(count(p.id())).isEqualTo(3);
    }
    @Test void rollbackRecoversAndDatabaseUniquenessProtectsOccurrences(){
        var a=wallet();var b=wallet();String key=UUID.randomUUID().toString();var input=input(a,b,"12.34");
        new TransactionTemplate(manager).executeWithoutResult(status->{plans.create(a.user(),key,input);status.setRollbackOnly();});
        assertThat(plans.list(a.user(),0,20)).isEmpty();var p=plans.create(a.user(),key,input);assertThat(count(p.id())).isEqualTo(3);
        assertThatThrownBy(()->db.update("INSERT INTO scheduled_payments(user_id,sender_account_id,recipient_account_id,amount,description,execute_at,request_key,request_json,recurring_plan_id,occurrence_index) SELECT user_id,sender_account_id,recipient_account_id,amount,description,execute_at,CONCAT('duplicate/',id),request_json,recurring_plan_id,occurrence_index FROM scheduled_payments WHERE recurring_plan_id=? LIMIT 1",p.id())).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_recurring_occurrence");
    }
    @Test void endpointsValidateOwnershipAndContracts()throws Exception{
        var a=wallet();var b=wallet();var other=wallet();var p=plan(a,b,"12.34");
        mvc.perform(get("/api/recurring-payments")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/recurring-payments/"+p.id()).header("Authorization","Bearer "+other.token())).andExpect(status().isNotFound());
        mvc.perform(post("/api/recurring-payments/"+p.id()+"/cancel").header("Authorization","Bearer "+other.token())).andExpect(status().isNotFound());
        mvc.perform(get("/api/recurring-payments/"+p.id()+"/occurrences").header("Authorization","Bearer "+a.token())).andExpect(status().isOk()).andExpect(jsonPath("$[0].amount").value("12.34")).andExpect(jsonPath("$[0].recurringPlanId").value(p.id()));
        assertThatThrownBy(()->plans.create(other.user(),UUID.randomUUID().toString(),input(a,b,"12.34"))).hasMessageContaining("cannot use this account");
        var eur=accounts.create(a.user(),new CreateAccountRequest("CHECKING","EUR",BigDecimal.TEN));
        var mixed=new RecurringPaymentService.Request(eur.id(),b.account(),BigDecimal.ONE,null,Instant.now().plusSeconds(60),DAILY,"UTC",2);
        assertThatThrownBy(()->plans.create(a.user(),UUID.randomUUID().toString(),mixed)).hasMessageContaining("same currency");
    }
}
