package com.portfolio.fraud.dto;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

public record ConfirmationRequest(
    @JsonProperty(access=JsonProperty.Access.WRITE_ONLY)
    @NotBlank @Size(max=72) String password) {
    @Override public String toString() { return "ConfirmationRequest[redacted]"; }
}
