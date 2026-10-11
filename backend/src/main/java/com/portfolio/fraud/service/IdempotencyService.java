package com.portfolio.fraud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.fraud.dto.TransactionDtos.TransactionResponse;
import com.portfolio.fraud.entity.User;
import com.portfolio.fraud.exception.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IdempotencyService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final byte[] secret;
    public IdempotencyService(JdbcTemplate db,ObjectMapper json,PlatformTransactionManager manager,@Value("${app.jwt.secret}") String secret) {
        this.db=db;this.json=json;this.secret=secret.getBytes(StandardCharsets.UTF_8);
        transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(2);
    }
    public TransactionResponse execute(User user,String operation,String key,Object request,Supplier<TransactionResponse> work) {
        if(key==null || !key.matches("[A-Za-z0-9_-]{16,80}"))
            throw new ApiException(HttpStatus.BAD_REQUEST,"IDEMPOTENCY_KEY_REQUIRED","Provide a unique Idempotency-Key (16–80 letters, digits, hyphens or underscores) and reuse it when retrying this request.");
        String fingerprint=fingerprint(request);
        Object result=transaction.execute(status->{
            db.update("INSERT INTO idempotency_records(user_id,operation,request_key,fingerprint) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE request_key=request_key",user.getId(),operation,key,fingerprint);
            var row=db.queryForMap("SELECT fingerprint,response_json FROM idempotency_records WHERE user_id=? AND operation=? AND request_key=? FOR UPDATE",user.getId(),operation,key);
            if(!fingerprint.equals(row.get("fingerprint")))throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","This key was already used with different request content. Start a new operation.");
            if(row.get("response_json")!=null) return decode(row.get("response_json").toString());
            try {
                var response=work.get();
                db.update("UPDATE idempotency_records SET response_json=? WHERE user_id=? AND operation=? AND request_key=?",encode(response),user.getId(),operation,key);
                return response;
            } catch(ApiException denied) {
                // Domain rejections are outcomes too. Commit rate-limit/audit writes
                // once and retain only the public error, never request credentials.
                db.update("UPDATE idempotency_records SET response_json=? WHERE user_id=? AND operation=? AND request_key=?",encodeFailure(denied),user.getId(),operation,key);
                return denied;
            }
        });
        if(result instanceof ApiException denied)throw denied;
        return (TransactionResponse)result;
    }
    private String fingerprint(Object request) {
        try {var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(json.writeValueAsBytes(request)));}
        catch(Exception e){throw new IllegalStateException("Unable to fingerprint request");}
    }
    private String encode(TransactionResponse response){try{return json.writeValueAsString(response);}catch(Exception e){throw new IllegalStateException("Unable to encode outcome");}}
    private String encodeFailure(ApiException e){try{return json.writeValueAsString(Map.of("failure",true,"status",e.status.value(),"code",e.code,"message",e.getMessage()));}catch(Exception ignored){throw new IllegalStateException("Unable to encode rejection");}}
    private Object decode(String response){try{var tree=json.readTree(response);if(tree.path("failure").asBoolean())return new ApiException(HttpStatus.valueOf(tree.path("status").asInt()),tree.path("code").asText(),tree.path("message").asText());return json.treeToValue(tree,TransactionResponse.class);}catch(Exception e){throw new IllegalStateException("Unable to read outcome");}}
}
