package com.portfolio.fraud.service;

import com.portfolio.fraud.entity.Role;
import com.portfolio.fraud.entity.User;
import com.portfolio.fraud.exception.ApiException;
import java.time.Instant;
import java.util.Locale;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AdminUsersService {
    private final JdbcTemplate db;
    public AdminUsersService(JdbcTemplate db) { this.db=db; }

    // Deliberately not a User entity: this is the entire public directory contract.
    public record UserSummary(long id,String fullName,String email,String role,Instant createdAt) {}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<UserSummary> list(User actor,String search,int page,int size) {
        if(actor==null || actor.getRole()!=Role.ADMIN)
            throw new ApiException(HttpStatus.FORBIDDEN,"ADMIN_REQUIRED","Administrator access is required.");
        if(page<0 || size<1 || size>100)
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PAGE","Page must be nonnegative and size between 1 and 100.");
        String query=search==null?"":search.strip().toLowerCase(Locale.ROOT);
        if(query.length()>255)
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_SEARCH","Search must not exceed 255 characters.");
        // LOCATE treats %, _ and quotes as literal search text, not SQL wildcards.
        String where=" WHERE (?='' OR LOCATE(?,LOWER(full_name))>0 OR LOCATE(?,LOWER(email))>0 OR LOWER(role)=? OR CAST(id AS CHAR)=?)";
        long total=db.queryForObject("SELECT COUNT(*) FROM users"+where,Long.class,query,query,query,query,query);
        var rows=db.query("SELECT id,full_name,email,role,created_at FROM users"+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            (rs,n)->new UserSummary(rs.getLong("id"),rs.getString("full_name"),rs.getString("email"),rs.getString("role"),rs.getTimestamp("created_at").toInstant()),
            query,query,query,query,query,size,(long)page*size);
        return new PageImpl<>(rows,PageRequest.of(page,size),total);
    }
}
