package com.portfolio.fraud.service;

import com.portfolio.fraud.entity.User;
import com.portfolio.fraud.exception.*;
import com.portfolio.fraud.repository.ConfirmationGuardRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.*;

@Service
public class SessionService {
    private final JdbcTemplate db;private final PasswordEncoder encoder;private final ConfirmationGuardRepository guards;private final LedgerJournal journal;
    @Value("${app.jwt.expiration-ms}")private long lifetime;
    public SessionService(JdbcTemplate db,PasswordEncoder e,ConfirmationGuardRepository g,LedgerJournal j){this.db=db;encoder=e;guards=g;journal=j;}
    @Transactional public String create(User user) {
        String id=UUID.randomUUID().toString();Instant now=Instant.now();
        var attributes=RequestContextHolder.getRequestAttributes();
        HttpServletRequest request=attributes instanceof ServletRequestAttributes a?a.getRequest():null;
        String agent=request==null?"Unavailable":Objects.toString(request.getHeader("User-Agent"),"Unavailable");
        String address=request==null?"Unavailable":request.getRemoteAddr();
        db.update("INSERT INTO user_sessions(id,user_id,created_at,last_activity_at,expires_at,user_agent,remote_address) VALUES(?,?,?,?,?,?,?)",id,user.getId(),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now.plusMillis(lifetime)),agent.substring(0,Math.min(255,agent.length())),address.substring(0,Math.min(80,address.length())));
        journal.audit(user.getId(),"SESSION_CREATED",null,null,"A new authenticated session was created.");return id;
    }
    public boolean active(String id,Long userId) {
        if(id==null)return false;
        return db.update("UPDATE user_sessions SET last_activity_at=CURRENT_TIMESTAMP(6) WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>CURRENT_TIMESTAMP(6)",id,userId)>0;
    }
    public List<Map<String,Object>> list(User user,String current){var rows=db.queryForList("SELECT id,created_at AS createdAt,last_activity_at AS lastActivityAt,expires_at AS expiresAt,user_agent AS userAgent,remote_address AS remoteAddress FROM user_sessions WHERE user_id=? AND revoked_at IS NULL AND expires_at>CURRENT_TIMESTAMP(6) ORDER BY created_at DESC",user.getId());rows.forEach(r->r.put("current",current.equals(r.get("id"))));return rows;}
    @Transactional public void revoke(User user,String id){if(db.update("UPDATE user_sessions SET revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP(6)) WHERE user_id=? AND id=?",user.getId(),id)==0)throw new ApiException(HttpStatus.NOT_FOUND,"SESSION_NOT_FOUND","Session not found.");journal.audit(user.getId(),"SESSION_REVOKED",null,null,"An authenticated session was revoked.");}
    @Transactional public void others(User user,String current){db.update("UPDATE user_sessions SET revoked_at=CURRENT_TIMESTAMP(6) WHERE user_id=? AND id<>? AND revoked_at IS NULL",user.getId(),current);journal.audit(user.getId(),"OTHER_SESSIONS_REVOKED",null,null,"Other sessions were revoked.");}
    @Transactional(noRollbackFor=ConfirmationDenied.class)
    public void changePassword(User user,String current,String next) {
        if(next==null||next.length()<8||next.getBytes(StandardCharsets.UTF_8).length>72)throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PASSWORD","Use at least 8 characters and no more than 72 UTF-8 bytes.");
        Instant now=Instant.now();guards.ensureExists(user.getId(),now);var guard=guards.lock(user.getId());if(guard.isLocked(now))throw new ConfirmationDenied(true);
        String hash=db.queryForObject("SELECT password_hash FROM users WHERE id=? FOR UPDATE",String.class,user.getId());
        if(current==null||current.getBytes(StandardCharsets.UTF_8).length>72||!encoder.matches(current,hash)){guard.failed(now,5,300,60);journal.audit(user.getId(),"PASSWORD_CHANGE_DENIED",null,null,"Current password was not accepted.");throw new ConfirmationDenied(guard.isLocked(now));}
        if(encoder.matches(next,hash))throw new ApiException(HttpStatus.BAD_REQUEST,"PASSWORD_UNCHANGED","Choose a password different from your current password.");
        guard.succeeded(now);db.update("UPDATE users SET password_hash=? WHERE id=?",encoder.encode(next),user.getId());
        db.update("UPDATE user_sessions SET revoked_at=CURRENT_TIMESTAMP(6) WHERE user_id=? AND revoked_at IS NULL",user.getId());
        journal.audit(user.getId(),"PASSWORD_CHANGED",null,null,"Password changed; all existing sessions revoked.");
    }
}
