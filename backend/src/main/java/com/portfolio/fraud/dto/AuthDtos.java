package com.portfolio.fraud.dto;
import jakarta.validation.constraints.*;

public final class AuthDtos {
    private AuthDtos() {}
    public record RegisterRequest(
        @NotBlank @Email @Size(max=255) String email,
        @NotBlank @Size(min=8,max=72) String password,
        @NotBlank @Size(max=120) String fullName) {
        @Override public String toString() { return "RegisterRequest[redacted]"; }
    }
    public record LoginRequest(
        @NotBlank @Email @Size(max=255) String email,
        @NotBlank @Size(max=72) String password) {
        @Override public String toString() { return "LoginRequest[redacted]"; }
    }
    public record AuthResponse(String token,String email,String role,String fullName) {
        @Override public String toString() { return "AuthResponse[redacted]"; }
    }
}
