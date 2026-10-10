package com.portfolio.fraud.controller;

import com.portfolio.fraud.dto.TransactionDtos.TransactionResponse;
import com.portfolio.fraud.dto.FraudDtos.AnalysisResponse;
import com.portfolio.fraud.entity.*;
import com.portfolio.fraud.repository.TransactionRepository;
import com.portfolio.fraud.service.TransactionService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final TransactionRepository repository;
    private final TransactionService transactions;
    public AdminController(TransactionRepository repository,TransactionService transactions) {
        this.repository=repository; this.transactions=transactions;
    }
    @GetMapping("/dashboard")
    public Dashboard dashboard() {
        Map<String,Long> status=new LinkedHashMap<>(),type=new LinkedHashMap<>(),risk=new LinkedHashMap<>();
        for (TransactionStatus value:TransactionStatus.values()) status.put(value.name(),repository.countByStatus(value));
        for (TransactionType value:TransactionType.values()) type.put(value.name(),repository.countByType(value));
        for (RiskLevel value:RiskLevel.values()) risk.put(value.name(),repository.countByRiskLevel(value));
        Map<String,BigDecimal> volume=new TreeMap<>();
        repository.completedVolumeByCurrency().forEach(row->volume.put((String)row[0],(BigDecimal)row[1]));
        return new Dashboard(repository.count(),volume,status,type,risk);
    }
    @GetMapping({"/transactions","/fraud"})
    public Page<TransactionResponse> monitoring(
        jakarta.servlet.http.HttpServletRequest request,
        @RequestParam(required=false) TransactionType type,
        @RequestParam(required=false) TransactionStatus status,
        @RequestParam(required=false) RiskLevel risk,
        @RequestParam(required=false) Instant from,
        @RequestParam(required=false) Instant to,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return transactions.monitoring(type,status,risk,from,to,page,size,request.getRequestURI().endsWith("/fraud"));
    }
    @GetMapping("/transactions/{id}")
    public TransactionResponse detail(@AuthenticationPrincipal User user,@PathVariable Long id) {
        return transactions.detail(user,id);
    }
    @GetMapping("/transactions/{id}/fraud")
    public AnalysisResponse fraud(@AuthenticationPrincipal User user,@PathVariable Long id) {
        return transactions.fraudDetail(user,id);
    }
    public record Dashboard(long totalTransactions,Map<String,BigDecimal> completedVolumeByCurrency,
        Map<String,Long> byStatus,Map<String,Long> byType,Map<String,Long> byRisk) {}
}
