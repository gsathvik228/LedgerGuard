package com.portfolio.fraud.controller;

import com.portfolio.fraud.entity.User;
import com.portfolio.fraud.service.AdminUsersService;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/admin/users")
public class AdminUsersController {
    private final AdminUsersService users;
    public AdminUsersController(AdminUsersService users) { this.users=users; }

    @GetMapping
    public Page<AdminUsersService.UserSummary> list(@AuthenticationPrincipal User actor,
        @RequestParam(defaultValue="") String search,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return users.list(actor,search,page,size);
    }
}
