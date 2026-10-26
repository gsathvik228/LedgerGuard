package com.portfolio.fraud.controller;

import com.portfolio.fraud.entity.User;
import com.portfolio.fraud.repository.UserRepository;
import com.portfolio.fraud.security.*;
import com.portfolio.fraud.service.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.domain.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminUsersController.class) @Import(SecurityConfig.class)
class AdminUsersControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AdminUsersService directory;
    @MockitoBean JwtService jwt;
    @MockitoBean UserRepository users;
    @MockitoBean SessionService sessions;

    @Test void anonymousCannotReadDirectory() throws Exception {
        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());verifyNoInteractions(directory);
    }
    @Test void customerCannotReadDirectory() throws Exception {
        mvc.perform(get("/api/admin/users").with(user("customer").roles("CUSTOMER"))).andExpect(status().isForbidden());verifyNoInteractions(directory);
    }
    @Test void adminReceivesOnlyAllowlistedFields() throws Exception {
        var admin=User.developmentAdmin("admin@example.test","never-expose-this-hash");
        var auth=new UsernamePasswordAuthenticationToken(admin,null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        when(directory.list(admin,"Alice",0,10)).thenReturn(new PageImpl<>(List.of(new AdminUsersService.UserSummary(12,"Alice","alice@example.test","CUSTOMER",Instant.parse("2026-01-02T03:04:05Z"))),PageRequest.of(0,10),1));
        mvc.perform(get("/api/admin/users").param("search","Alice").param("size","10").with(authentication(auth)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].length()").value(5))
            .andExpect(jsonPath("$.content[0].id").value(12)).andExpect(jsonPath("$.content[0].fullName").value("Alice"))
            .andExpect(jsonPath("$.content[0].email").value("alice@example.test")).andExpect(jsonPath("$.content[0].role").value("CUSTOMER"))
            .andExpect(jsonPath("$.content[0].createdAt").value("2026-01-02T03:04:05Z"))
            .andExpect(jsonPath("$.content[0].passwordHash").doesNotExist()).andExpect(jsonPath("$.content[0].token").doesNotExist());
    }
}
