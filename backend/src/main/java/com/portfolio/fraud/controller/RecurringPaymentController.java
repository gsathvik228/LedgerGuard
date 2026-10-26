package com.portfolio.fraud.controller;

import com.portfolio.fraud.entity.User;
import com.portfolio.fraud.service.*;
import com.portfolio.fraud.service.RecurringPaymentService.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/recurring-payments")
public class RecurringPaymentController {
    private final RecurringPaymentService plans;
    public RecurringPaymentController(RecurringPaymentService plans){this.plans=plans;}
    @PostMapping public Plan create(@AuthenticationPrincipal User user,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Request request){return plans.create(user,key,request);}
    @GetMapping public List<Plan> list(@AuthenticationPrincipal User user,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return plans.list(user,page,size);}
    @GetMapping("/{id}") public Plan detail(@AuthenticationPrincipal User user,@PathVariable Long id){return plans.detail(user,id);}
    @GetMapping("/{id}/occurrences") public List<ScheduledPaymentService.Payment> occurrences(@AuthenticationPrincipal User user,@PathVariable Long id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return plans.occurrences(user,id,page,size);}
    @PostMapping("/{id}/cancel") public Plan cancel(@AuthenticationPrincipal User user,@PathVariable Long id){return plans.cancel(user,id);}
}
