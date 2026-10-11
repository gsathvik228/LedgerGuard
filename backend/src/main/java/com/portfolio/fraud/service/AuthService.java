package com.portfolio.fraud.service;
import com.portfolio.fraud.dto.AuthDtos.*;
import com.portfolio.fraud.entity.User;
import com.portfolio.fraud.exception.ApiException;
import com.portfolio.fraud.repository.UserRepository;
import com.portfolio.fraud.security.JwtService;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final org.springframework.jdbc.core.JdbcTemplate db;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    public AuthService(UserRepository users,PasswordEncoder encoder,JwtService jwt,org.springframework.jdbc.core.JdbcTemplate db) {
        this.db=db;this.users=users; this.encoder=encoder; this.jwt=jwt;
    }
    @org.springframework.transaction.annotation.Transactional
    public AuthResponse register(RegisterRequest request) {
        checkPassword(request.password());
        String email=request.email().strip().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email))
            throw new ApiException(HttpStatus.CONFLICT,"EMAIL_EXISTS","An account with this email already exists");
        return response(users.save(new User(email,encoder.encode(request.password()),request.fullName().strip())));
    }
    @org.springframework.transaction.annotation.Transactional
    public AuthResponse login(LoginRequest request) {
        checkPassword(request.password());
        User user=users.findByEmail(request.email().strip().toLowerCase(Locale.ROOT))
            .orElseThrow(this::invalid);
        String currentHash=db.queryForObject("SELECT password_hash FROM users WHERE id=? FOR UPDATE",String.class,user.getId());
        if (!encoder.matches(request.password(),currentHash)) throw invalid();
        return response(user);
    }
    private void checkPassword(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length>72)
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PASSWORD","Password must not exceed 72 UTF-8 bytes");
    }
    private ApiException invalid() { return new ApiException(HttpStatus.UNAUTHORIZED,"INVALID_CREDENTIALS","Invalid email or password"); }
    private AuthResponse response(User user) { return new AuthResponse(jwt.create(user),user.getEmail(),user.getRole().name(),user.getFullName()); }
}
