package com.portfolio.fraud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.fraud.entity.User;
import com.portfolio.fraud.repository.AccountRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.portfolio.fraud.service.Recurrence.Frequency.*;

class RecurringPaymentServiceTest {
    final Instant now=Instant.parse("2026-10-26T12:00:00Z");
    JdbcTemplate db;ScheduledPaymentService schedules;RecurringPaymentService service;User user;
    @BeforeEach void setup(){
        db=mock(JdbcTemplate.class);schedules=mock(ScheduledPaymentService.class);
        service=new RecurringPaymentService(db,mock(AccountRepository.class),schedules,new ObjectMapper().findAndRegisterModules(),Clock.fixed(now,ZoneOffset.UTC),mock(LedgerJournal.class));
        user=new User("test@example.test","hash","Test");ReflectionTestUtils.setField(user,"id",1L);
    }
    RecurringPaymentService.Request request(Instant first,Recurrence.Frequency frequency,String zone,int count){return new RecurringPaymentService.Request(10L,20L,BigDecimal.TEN,"Recurring",first,frequency,zone,count);}
    @Test void validatesFiniteCountDateZoneAndDatabaseDateRange(){
        assertThatThrownBy(()->service.create(user,null,request(now.plusSeconds(60),DAILY,"UTC",2))).hasMessageContaining("Idempotency-Key");
        for(int count:List.of(0,121))assertThatThrownBy(()->service.create(user,"recurring-key-12345",request(now.plusSeconds(60),DAILY,"UTC",count))).hasMessageContaining("120");
        assertThatThrownBy(()->service.create(user,"recurring-key-12345",request(now,DAILY,"UTC",2))).hasMessageContaining("future");
        assertThatThrownBy(()->service.create(user,"recurring-key-12345",request(now.plusSeconds(60),DAILY,"Not/AZone",2))).hasMessageContaining("time zone");
        assertThatThrownBy(()->service.create(user,"recurring-key-12345",request(now.plusSeconds(60),YEARLY,"UTC",120))).hasMessageContaining("2038");
        verifyNoInteractions(schedules);
    }
    Map<String,Object> plan(Instant first,int count,int index,String frequency){return Map.of("user_id",1L,"sender_account_id",10L,"recipient_account_id",20L,"amount",BigDecimal.TEN,"description","Recurring","first_execute_at",Timestamp.from(first),"occurrence_count",count,"next_index",index,"frequency",frequency,"time_zone","UTC");}
    @Test void fortyFiveDayHorizonDoesNotQueueAnUnboundedSeries(){
        when(db.queryForList(contains("FOR UPDATE"),eq(7L))).thenReturn(List.of(plan(now.plusSeconds(60),120,0,"DAILY")));
        service.generate(7L);
        verify(schedules,times(45)).materialize(eq(1L),eq(7L),anyInt(),any());
        verify(db).update(contains("SET next_index"),eq(45),eq(Timestamp.from(now.plusSeconds(60).plus(Duration.ofDays(45)))),eq(7L));
    }
    @Test void generationResumesAtPersistentIndexAndHonorsCount(){
        when(db.queryForList(contains("FOR UPDATE"),eq(7L))).thenReturn(List.of(plan(now.plusSeconds(60),3,2,"DAILY")));
        service.generate(7L);verify(schedules).materialize(eq(1L),eq(7L),eq(2),any());
        verify(db).update(contains("SET next_index"),eq(3),isNull(),eq(7L));
    }
    @Test void cancelledMissingAndExhaustedPlansNeverCreateMoreOccurrences(){
        service.generate(7L);verifyNoInteractions(schedules);
        when(db.queryForList(contains("FOR UPDATE"),eq(7L))).thenReturn(List.of(plan(now.plusSeconds(60),3,3,"DAILY")));
        service.generate(7L);verifyNoInteractions(schedules);
    }
}
