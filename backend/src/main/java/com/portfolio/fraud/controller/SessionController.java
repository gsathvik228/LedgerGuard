package com.portfolio.fraud.controller;
import com.portfolio.fraud.entity.User;
import com.portfolio.fraud.service.SessionService;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/security")
public class SessionController {
    private final SessionService sessions;public SessionController(SessionService s){sessions=s;}
    public record PasswordChange(@NotBlank @JsonProperty(access=JsonProperty.Access.WRITE_ONLY)String currentPassword,@NotBlank @Size(min=8,max=72) @JsonProperty(access=JsonProperty.Access.WRITE_ONLY)String newPassword){@Override public String toString(){return "PasswordChange[REDACTED]";}}
    @GetMapping("/sessions")public Object list(@AuthenticationPrincipal User u,HttpServletRequest r){return sessions.list(u,(String)r.getAttribute("sessionId"));}
    @DeleteMapping("/sessions/{id}")public void revoke(@AuthenticationPrincipal User u,@PathVariable String id){sessions.revoke(u,id);}
    @PostMapping("/sessions/revoke-others")public void others(@AuthenticationPrincipal User u,HttpServletRequest r){sessions.others(u,(String)r.getAttribute("sessionId"));}
    @PostMapping("/logout")public void logout(@AuthenticationPrincipal User u,HttpServletRequest r){sessions.revoke(u,(String)r.getAttribute("sessionId"));}
    @PostMapping("/password")public void password(@AuthenticationPrincipal User u,@Valid @RequestBody PasswordChange body){sessions.changePassword(u,body.currentPassword(),body.newPassword());}
}
