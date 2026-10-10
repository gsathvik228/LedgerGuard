package com.portfolio.fraud.fraud;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.portfolio.fraud.entity.*; import com.portfolio.fraud.repository.TransactionRepository; import java.math.BigDecimal; import org.junit.jupiter.api.Test;
class FraudDetectionServiceTest {
 @Test void flagsAmountAboveHistoricalAverage(){TransactionRepository repo=mock(TransactionRepository.class);when(repo.averageCompletedAmount(any())).thenReturn(new BigDecimal("100"));FraudDetectionService service=new FraudDetectionService(repo,new BigDecimal("10000"),new BigDecimal("3"),5,3,3);User user=new User("a@b.com","hash","A");Account account=new Account("123",user,"CHECKING",new BigDecimal("10000"));var result=service.evaluate(user,account,null,new BigDecimal("500"));assertThat(result.findings()).extracting(FraudDetectionService.Finding::rule).contains("UNUSUAL_AMOUNT");}
 @Test void normalAmountHasLowRisk(){TransactionRepository repo=mock(TransactionRepository.class);when(repo.averageCompletedAmount(any())).thenReturn(new BigDecimal("100"));FraudDetectionService service=new FraudDetectionService(repo,new BigDecimal("10000"),new BigDecimal("3"),5,3,3);User user=new User("a@b.com","hash","A");Account account=new Account("123",user,"CHECKING",new BigDecimal("10000"));var result=service.evaluate(user,account,null,new BigDecimal("100"));assertThat(result.level()).isEqualTo(RiskLevel.LOW);}
}
