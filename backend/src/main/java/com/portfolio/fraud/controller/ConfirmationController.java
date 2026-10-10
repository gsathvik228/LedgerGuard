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
    private final ConfirmationService confirmations;
    public ConfirmationController(ConfirmationService confirmations) { this.confirmations=confirmations; }
    @PostMapping("/{id}/confirm")
    public TransactionResponse confirm(@AuthenticationPrincipal User user,@PathVariable Long id,@Valid @RequestBody ConfirmationRequest request) {
        return confirmations.confirm(user,id,request.password());
    }
    @PostMapping("/{id}/cancel")
    public TransactionResponse cancel(@AuthenticationPrincipal User user,@PathVariable Long id) {
        return confirmations.cancel(user,id);
    }
}
