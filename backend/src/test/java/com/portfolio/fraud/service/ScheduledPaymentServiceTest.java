package com.portfolio.fraud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.fraud.dto.TransactionDtos.*;
import com.portfolio.fraud.entity.*;
import com.portfolio.fraud.exception.ApiException;
import com.portfolio.fraud.repository.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScheduledPaymentServiceTest {
    JdbcTemplate db;
    AccountRepository accounts;
    UserRepository users;
    TransactionService transfers;
    ConfirmationService confirmations;
    LedgerJournal journal;
    ScheduledPaymentService service;
    User owner;
    @BeforeEach void setup(){
        db=mock(JdbcTemplate.class);accounts=mock(AccountRepository.class);users=mock(UserRepository.class);
        transfers=mock(TransactionService.class);confirmations=mock(ConfirmationService.class);journal=mock(LedgerJournal.class);
        service=new ScheduledPaymentService(db,accounts,users,transfers,confirmations,journal,new ObjectMapper().findAndRegisterModules());
        owner=new User("schedule@example.test","hash","Scheduled user");ReflectionTestUtils.setField(owner,"id",1L);
    }
    ScheduledPaymentService.Request request(Instant at){return new ScheduledPaymentService.Request(10L,20L,new BigDecimal("12.34"),"Later",at);}
    Map<String,Object> due(){
        return Map.of("user_id",1L,"sender_account_id",10L,"recipient_account_id",20L,"amount",new BigDecimal("12.34"),"description","Later");
    }
    void dueSetup(){
        when(db.queryForList(contains("execute_at<="),eq(7L))).thenReturn(List.of(due()));
        when(users.findById(1L)).thenReturn(Optional.of(owner));
    }
    TransactionResponse response(String status){
        return new TransactionResponse(30L,"TRANSFER",new BigDecimal("12.34"),status,"LOW",0,"Later",Instant.now(),10L,20L,"USD",null,null,null,false,null,false);
    }
    @Test void notDueCancelledOrAlreadyProcessedNeverTransfers(){
        when(db.queryForList(contains("execute_at<="),eq(7L))).thenReturn(List.of());
        service.execute(7L);verifyNoInteractions(transfers,users,journal);
    }
    @Test void allRiskOutcomesLinkToExistingTransferWorkflow(){
        dueSetup();
        for(String status:List.of("COMPLETED","PENDING_CONFIRMATION","AWAITING_REVIEW")){
            when(transfers.transfer(eq(owner),any())).thenReturn(response(status));
            service.execute(7L);
        }
        verify(transfers,times(3)).transfer(owner,new TransferRequest(10L,20L,new BigDecimal("12.34"),"Later"));
        verify(db,times(3)).update(contains("status='PROCESSED'"),eq(30L),eq(7L));
        verifyNoInteractions(confirmations);
    }
    @Test void insufficientFundsRecordsFailureWithoutAutomaticRetry(){
        dueSetup();
        when(transfers.transfer(eq(owner),any())).thenThrow(new ApiException(HttpStatus.BAD_REQUEST,"INSUFFICIENT_BALANCE","Insufficient balance"));
        service.execute(7L);
        verify(db).update(contains("status='FAILED'"),eq("Insufficient balance"),eq(7L));
        verify(db,never()).update(contains("status='PROCESSED'"),any(),any());
    }
    @Test void infrastructureFailuresEscapeSoEntireUnitRollsBack(){
        dueSetup();when(transfers.transfer(eq(owner),any())).thenThrow(new IllegalStateException("Database unavailable"));
        assertThatThrownBy(()->service.execute(7L)).hasMessage("Database unavailable");
        verify(db,never()).update(contains("status='FAILED'"),any(),any());
        verifyNoInteractions(journal);
    }
    @Test void rejectsMissingKeyPastDateSameAccountAndOtherOwners(){
        assertThatThrownBy(()->service.create(owner,null,request(Instant.now().plusSeconds(60)))).isInstanceOf(ApiException.class);
        when(db.queryForList(contains("request_key=?"),eq(1L),eq("schedule-key-123456"))).thenReturn(List.of());
        assertThatThrownBy(()->service.create(owner,"schedule-key-123456",request(Instant.now().minusSeconds(1)))).hasMessage("Choose an execution time in the future.");
        var same=new ScheduledPaymentService.Request(10L,10L,BigDecimal.ONE,null,Instant.now().plusSeconds(60));
        assertThatThrownBy(()->service.create(owner,"schedule-key-123456",same)).hasMessage("Choose a different recipient account.");
        User other=new User("other@example.test","hash","Other");ReflectionTestUtils.setField(other,"id",2L);
        when(accounts.findById(10L)).thenReturn(Optional.of(new Account("LG10",other,"CHECKING",BigDecimal.ZERO,"USD")));
        when(accounts.findById(20L)).thenReturn(Optional.of(new Account("LG20",owner,"CHECKING",BigDecimal.ZERO,"USD")));
        assertThatThrownBy(()->service.create(owner,"schedule-key-123456",request(Instant.now().plusSeconds(60)))).hasMessage("You cannot use this account.");
        verifyNoInteractions(transfers);
    }
    @Test void conflictingRetryRejectedEvenIfOriginalTimeHasPassed(){
        when(db.queryForList(contains("request_key=?"),eq(1L),eq("schedule-key-123456")))
            .thenReturn(List.of(Map.of("id",7L,"request_json","different")));
        assertThatThrownBy(()->service.create(owner,"schedule-key-123456",request(Instant.now().minusSeconds(1))))
            .isInstanceOf(ApiException.class).hasMessage("This key was used for a different schedule.");
        verifyNoInteractions(accounts,transfers);
    }
    @Test void unrelatedUsersCannotReadOrCancel(){
        when(db.queryForList(anyString(),eq(7L),eq(1L))).thenReturn(List.of());
        assertThatThrownBy(()->service.detail(owner,7L)).hasMessage("Scheduled payment not found.");
        assertThatThrownBy(()->service.cancel(owner,7L)).hasMessage("Scheduled payment not found.");
        verifyNoInteractions(transfers,confirmations);
    }
    @Test void paginationIsBounded(){
        assertThatThrownBy(()->service.list(owner,-1,20)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.list(owner,0,101)).isInstanceOf(ApiException.class);
        verifyNoInteractions(db);
    }
    @Test void workerContinuesAfterOneScheduleRollsBack(){
        when(db.queryForList(anyString(),eq(Long.class))).thenReturn(List.of(7L,8L));
        var payments=mock(ScheduledPaymentService.class);
        doThrow(new IllegalStateException("Transient test failure")).when(payments).execute(7L);
        new ScheduledPaymentWorker(db,payments).processDue();
        verify(payments).execute(8L);
    }
}
