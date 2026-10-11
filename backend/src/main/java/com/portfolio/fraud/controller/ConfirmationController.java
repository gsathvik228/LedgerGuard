package com.portfolio.fraud.controller;
import com.portfolio.fraud.dto.ConfirmationRequest;
import com.portfolio.fraud.dto.TransactionDtos.TransactionResponse;
import com.portfolio.fraud.entity.User;
import com.portfolio.fraud.service.ConfirmationService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/transactions")
public class ConfirmationController {
    private final com.portfolio.fraud.service.IdempotencyService keys;
    private final ConfirmationService confirmations;
    public ConfirmationController(ConfirmationService confirmations,com.portfolio.fraud.service.IdempotencyService keys) { this.confirmations=confirmations;this.keys=keys; }
    @PostMapping("/{id}/confirm")
    public TransactionResponse confirm(@AuthenticationPrincipal User user,@PathVariable Long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ConfirmationRequest request) {
        return keys.execute(user,"confirm:"+id,key,java.util.Map.of("password",request.password()),()->confirmations.confirm(user,id,request.password()));
    }
    @PostMapping("/{id}/cancel")
    public TransactionResponse cancel(@AuthenticationPrincipal User user,@PathVariable Long id,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        return keys.execute(user,"cancel:"+id,key,id,()->confirmations.cancel(user,id));
    }
}
