package com.portfolio.fraud.controller;

import com.portfolio.fraud.entity.User;
import com.portfolio.fraud.service.ScheduledPaymentService;
import com.portfolio.fraud.service.ScheduledPaymentService.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/scheduled-payments")
public class ScheduledPaymentController {
    private final ScheduledPaymentService payments;
    public ScheduledPaymentController(ScheduledPaymentService payments){this.payments=payments;}
    @PostMapping public Payment create(@AuthenticationPrincipal User user,@RequestHeader(value="Idempotency-Key",required=false)String key,@Valid @RequestBody Request request){return payments.create(user,key,request);}
    @GetMapping public List<Payment> list(@AuthenticationPrincipal User user,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return payments.list(user,page,size);}
    @GetMapping("/{id}") public Payment detail(@AuthenticationPrincipal User user,@PathVariable Long id){return payments.detail(user,id);}
    @PostMapping("/{id}/cancel") public Payment cancel(@AuthenticationPrincipal User user,@PathVariable Long id){return payments.cancel(user,id);}
}
