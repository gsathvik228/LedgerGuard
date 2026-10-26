package com.portfolio.fraud.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Only internal resource types and IDs are accepted, never caller-provided URLs. */
@Service @Transactional(propagation=Propagation.MANDATORY)
public class ResourceEvents {
    public enum Type { PAYMENT_REQUEST, BILL, EXPENSE_GROUP }
    private final JdbcTemplate db;
    public ResourceEvents(JdbcTemplate db){this.db=db;}
    public void notify(Long user,Type type,Long id,String event,String message) {
        db.update("INSERT INTO notifications(user_id,resource_type,resource_id,event_key,message) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",user,type.name(),id,event,message);
    }
    public void audit(Long actor,String action,Type type,Long id,String previous,String next) {
        db.update("INSERT INTO audit_events(actor_id,action,previous_status,new_status,reason) VALUES(?,?,?,?,?)",actor,action,previous,next,type.name()+" #"+id);
    }
}
