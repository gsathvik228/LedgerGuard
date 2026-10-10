package com.portfolio.fraud.security;

import com.portfolio.fraud.entity.*;
import com.portfolio.fraud.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
@ConditionalOnProperty(name="app.dev-admin.enabled",havingValue="true")
public class DevelopmentAdmin implements CommandLineRunner {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String email,password;
    public DevelopmentAdmin(UserRepository users,PasswordEncoder encoder,
        @Value("${DEV_ADMIN_EMAIL:}") String email,@Value("${DEV_ADMIN_PASSWORD:}") String password) {
        this.users=users; this.encoder=encoder;
        this.email=email.strip().toLowerCase(Locale.ROOT); this.password=password;
    }
    public void run(String... args) {
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || password.length()<12 || password.getBytes(StandardCharsets.UTF_8).length>72)
            throw new IllegalStateException("Set a valid DEV_ADMIN_EMAIL and a 12-72 byte DEV_ADMIN_PASSWORD");
        var existing=users.findByEmail(email);
        if (existing.isPresent()) {
            if (existing.get().getRole()!=Role.ADMIN)
                throw new IllegalStateException("Development admin email belongs to a customer; choose a different email. No role changed.");
            return; // Never reset an existing user's credentials.
        }
        users.save(User.developmentAdmin(email,encoder.encode(password)));
    }
}
