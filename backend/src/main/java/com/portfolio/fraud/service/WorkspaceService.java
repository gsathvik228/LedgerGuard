package com.portfolio.fraud.service;

import com.portfolio.fraud.entity.*;
import com.portfolio.fraud.exception.ApiException;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly=true)
public class WorkspaceService {
    private final JdbcTemplate db;private final TransactionService transactions;
    public WorkspaceService(JdbcTemplate db,TransactionService tx){this.db=db;transactions=tx;}
    public record Results(List<Map<String,Object>> content,long totalElements,int totalPages,int number,int size){}
    public Results page(String select,String count,List<Object> parameters,int page,int size) {
        if(page<0||size<1||size>100)throw bad("Page must be nonnegative and size between 1 and 100.");
        long total=db.queryForObject(count,Long.class,parameters.toArray());
        var args=new ArrayList<>(parameters);args.add(size);args.add((long)page*size);
        return new Results(db.queryForList(select+" LIMIT ? OFFSET ?",args.toArray()),total,(int)((total+size-1)/size),page,size);
    }
    public Results timeline(User user,Long id,int page,int size) {
        transactions.detail(user,id);
        return page("SELECT id,action,previous_status AS previousStatus,new_status AS newStatus,reason,created_at AS createdAt FROM audit_events WHERE transaction_id=? ORDER BY id DESC","SELECT COUNT(*) FROM audit_events WHERE transaction_id=?",List.of(id),page,size);
    }
    public Results audit(Long actor,Long tx,String action,Instant from,Instant to,int page,int size) {
        if(from!=null&&to!=null&&from.isAfter(to))throw bad("Start must precede end.");
        String where=" WHERE 1=1";List<Object> args=new ArrayList<>();
        if(actor!=null){where+=" AND actor_id=?";args.add(actor);}if(tx!=null){where+=" AND transaction_id=?";args.add(tx);}
        if(action!=null&&!action.isBlank()){where+=" AND action=?";args.add(action);}
        if(from!=null){where+=" AND created_at>=?";args.add(Timestamp.from(from));}if(to!=null){where+=" AND created_at<=?";args.add(Timestamp.from(to));}
        return page("SELECT id,actor_id AS actorId,transaction_id AS transactionId,action,previous_status AS previousStatus,new_status AS newStatus,reason,created_at AS createdAt FROM audit_events"+where+" ORDER BY id DESC","SELECT COUNT(*) FROM audit_events"+where,args,page,size);
    }
    public Results notifications(User user,int page,int size){return page("SELECT id,transaction_id AS transactionId,resource_type AS resourceType,resource_id AS resourceId,message,created_at AS createdAt,read_at AS readAt FROM notifications WHERE user_id=? ORDER BY id DESC","SELECT COUNT(*) FROM notifications WHERE user_id=?",List.of(user.getId()),page,size);}
    public long unread(User user){return db.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id=? AND read_at IS NULL",Long.class,user.getId());}
    @Transactional public void read(User user,Long id){if(db.update("UPDATE notifications SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP(6)) WHERE id=? AND user_id=?",id,user.getId())==0)throw missing();}
    public List<Map<String,Object>> beneficiaries(User user){return db.queryForList("SELECT b.id,b.friendly_name AS friendlyName,a.id AS accountId,a.account_number AS accountNumber,a.currency FROM beneficiaries b JOIN accounts a ON a.id=b.account_id WHERE b.user_id=? ORDER BY b.friendly_name,b.id",user.getId());}
    @Transactional public void saveBeneficiary(User user,String number,String name) {
        name=validName(name);
        var rows=db.queryForList("SELECT id,user_id FROM accounts WHERE account_number=? AND status='ACTIVE'",number.strip());
        if(rows.isEmpty())throw bad("No active recipient account matches that number.");
        var row=rows.getFirst();if(((Number)row.get("user_id")).longValue()==user.getId())throw bad("An owned account cannot be saved as an external beneficiary.");
        db.update("INSERT INTO beneficiaries(user_id,account_id,friendly_name) VALUES(?,?,?)",user.getId(),row.get("id"),name);
    }
    @Transactional public void rename(User user,Long id,String name){if(db.update("UPDATE beneficiaries SET friendly_name=? WHERE id=? AND user_id=?",validName(name),id,user.getId())==0)throw missing();}
    @Transactional public void remove(User user,Long id){if(db.update("DELETE FROM beneficiaries WHERE id=? AND user_id=?",id,user.getId())==0)throw missing();}
    private String validName(String name){if(name==null||name.isBlank()||name.length()>80)throw bad("Enter a beneficiary name of 1–80 characters.");return name.strip();}
    private ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Record not found.");}
    private ApiException bad(String message){return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST",message);}
}
