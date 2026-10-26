package com.portfolio.fraud.dto;

import com.portfolio.fraud.entity.PaymentRequestStatus;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class PaymentRequestDtos {
    private PaymentRequestDtos() {}
    public record Create(@NotBlank @Email @Size(max=255) String recipientEmail,
        @NotNull @Positive Long receivingAccountId,
        @NotNull @DecimalMin("0.01") @Digits(integer=17,fraction=2) BigDecimal amount,
        @NotBlank @Size(max=255) String description,@Future Instant expiresAt) {}
    public record Pay(@NotNull @Positive Long senderAccountId) {}
    public record View(Long id,String requesterName,String recipientName,BigDecimal amount,String currency,
        String description,PaymentRequestStatus status,Instant createdAt,Instant updatedAt,Instant expiresAt,
        Instant resolvedAt,Long transactionId,boolean canPay,boolean canDecline,boolean canCancel) {}
    public record Attempt(Long transactionId,String status,Instant createdAt) {}
    public record Detail(View request,List<Attempt> attempts) {}
}
