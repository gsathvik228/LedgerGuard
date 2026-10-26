package com.portfolio.fraud.controller;

import com.portfolio.fraud.dto.PaymentRequestDtos.*;
import com.portfolio.fraud.dto.TransactionDtos.TransactionResponse;
import com.portfolio.fraud.entity.*;
import com.portfolio.fraud.service.*;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/payment-requests")
public class PaymentRequestController {
    private final PaymentRequestService service;private final IdempotencyService keys;
    public PaymentRequestController(PaymentRequestService service,IdempotencyService keys){this.service=service;this.keys=keys;}
    @PostMapping public View create(@AuthenticationPrincipal User user,@Valid @RequestBody Create input){return service.create(user,input);}
    @GetMapping public Page<View> list(@AuthenticationPrincipal User user,@RequestParam(defaultValue="received") String direction,@RequestParam(required=false) PaymentRequestStatus status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.list(user,direction,status,page,size);}
    @GetMapping("/{id}") public Detail detail(@AuthenticationPrincipal User user,@PathVariable Long id){return service.detail(user,id);}
    @PostMapping("/{id}/pay") public TransactionResponse pay(@AuthenticationPrincipal User user,@PathVariable Long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Pay input){return keys.execute(user,"payment-request-"+id,key,input,()->service.pay(user,id,input));}
    @PostMapping("/{id}/decline") public View decline(@AuthenticationPrincipal User user,@PathVariable Long id){return service.decide(user,id,false);}
    @PostMapping("/{id}/cancel") public View cancel(@AuthenticationPrincipal User user,@PathVariable Long id){return service.decide(user,id,true);}
}
