package com.portfolio.fraud.service;

import com.portfolio.fraud.entity.User;
import com.portfolio.fraud.exception.ApiException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class ReportService {
    private final JdbcTemplate db;public ReportService(JdbcTemplate db){this.db=db;}
    public record Movement(Long transactionId,Instant occurredAt,String type,String description,BigDecimal amount){}
    public record Statement(Long accountId,String accountNumber,String currency,LocalDate from,LocalDate to,Instant coverageStart,String provenance,BigDecimal openingBalance,List<Movement> movements,BigDecimal closingBalance){}
    private Map<String,Object> owned(User user,Long id){var rows=db.queryForList("SELECT a.id,a.account_number,a.currency,b.balance,b.recorded_at,b.provenance FROM accounts a JOIN account_baselines b ON b.account_id=a.id WHERE a.id=? AND a.user_id=?",id,user.getId());if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"ACCOUNT_NOT_FOUND","Owned account not found. Administrator access does not grant statement access to other accounts.");return rows.getFirst();}
    public Statement statement(User user,Long id,LocalDate from,LocalDate to) {
        range(from,to);var account=owned(user,id);Instant baseline=((Timestamp)account.get("recorded_at")).toInstant();
        if(from.isBefore(baseline.atZone(ZoneOffset.UTC).toLocalDate()))throw new ApiException(HttpStatus.CONFLICT,"HISTORY_UNAVAILABLE","Verified balance history starts on "+baseline+". Choose that UTC date or later; earlier balances cannot be reconstructed reliably.");
        Instant start=from.atStartOfDay(ZoneOffset.UTC).toInstant();if(start.isBefore(baseline))start=baseline;
        Instant end=to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        BigDecimal opening=((BigDecimal)account.get("balance")).add(db.queryForObject("SELECT COALESCE(SUM(amount),0) FROM ledger_movements WHERE account_id=? AND occurred_at>=? AND occurred_at<?",BigDecimal.class,id,Timestamp.from(baseline),Timestamp.from(start)));
        var movements=db.query("SELECT m.transaction_id,m.occurred_at,t.type,t.description,m.amount FROM ledger_movements m JOIN transactions t ON t.id=m.transaction_id WHERE m.account_id=? AND m.occurred_at>=? AND m.occurred_at<? AND t.status='COMPLETED' ORDER BY m.occurred_at,m.id",(rs,n)->new Movement(rs.getLong(1),rs.getTimestamp(2).toInstant(),rs.getString(3),Objects.toString(rs.getString(4),""),rs.getBigDecimal(5)),id,Timestamp.from(start),Timestamp.from(end));
        BigDecimal closing=opening.add(movements.stream().map(Movement::amount).reduce(BigDecimal.ZERO,BigDecimal::add));
        return new Statement(id,account.get("account_number").toString(),account.get("currency").toString(),from,to,start,account.get("provenance").toString(),opening,movements,closing);
    }
    public Object coverage(User user){return db.queryForList("SELECT a.id AS accountId,a.account_number AS accountNumber,a.currency,b.recorded_at AS coverageStart,b.provenance FROM accounts a JOIN account_baselines b ON b.account_id=a.id WHERE a.user_id=? ORDER BY a.id",user.getId());}
    public Object insights(User user,Long accountId,String currency,LocalDate from,LocalDate to) {
        range(from,to);if(accountId!=null)owned(user,accountId);
        String extra="";List<Object> args=new ArrayList<>(List.of(user.getId(),Timestamp.from(from.atStartOfDay(ZoneOffset.UTC).toInstant()),Timestamp.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())));
        if(accountId!=null){extra+=" AND a.id=?";args.add(accountId);}else extra+=" AND NOT (t.type='TRANSFER' AND s.user_id=a.user_id AND r.user_id=a.user_id)";
        if(currency!=null&&!currency.isBlank()){if(!currency.matches("[A-Z]{3}"))throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_CURRENCY","Use a three-letter currency code.");extra+=" AND a.currency=?";args.add(currency);}
        var rows=db.queryForList("SELECT DATE_FORMAT(m.occurred_at,'%Y-%m') AS month,a.currency,SUM(CASE WHEN m.amount>0 THEN m.amount ELSE 0 END) AS inflow,SUM(CASE WHEN m.amount<0 THEN -m.amount ELSE 0 END) AS outflow,SUM(m.amount) AS net FROM ledger_movements m JOIN accounts a ON a.id=m.account_id JOIN transactions t ON t.id=m.transaction_id LEFT JOIN accounts s ON s.id=t.sender_account_id LEFT JOIN accounts r ON r.id=t.recipient_account_id WHERE a.user_id=? AND m.occurred_at>=? AND m.occurred_at<? AND t.status='COMPLETED'"+extra+" GROUP BY month,a.currency ORDER BY month,a.currency",args.toArray());
        return Map.of("months",rows,"coverage",coverage(user),"note","Verified movements only, from each account's coverage start. Legacy pre-baseline activity is excluded. User-level summaries exclude transfers between your own accounts. Dates use UTC.");
    }
    private void range(LocalDate from,LocalDate to){if(from==null||to==null||from.isAfter(to)||from.isAfter(LocalDate.now(ZoneOffset.UTC))||java.time.temporal.ChronoUnit.DAYS.between(from,to)>3660)throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_DATE_RANGE","Choose an ordered UTC date range of no more than ten years, starting today or earlier.");}
    public static String csvCell(String value){String text=Objects.toString(value,"");if(text.stripLeading().matches("(?s)^[=+@\\-].*")||text.startsWith("\t")||text.startsWith("\r"))text="'"+text;return "\""+text.replace("\"","\"\"")+"\"";}
    public String csv(Statement s){StringBuilder out=new StringBuilder("Account,Currency,From UTC,To UTC,Coverage start,Provenance,Opening balance,Closing balance\r\n");out.append(csvCell(s.accountNumber())).append(',').append(csvCell(s.currency())).append(',').append(s.from()).append(',').append(s.to()).append(',').append(s.coverageStart()).append(',').append(s.provenance()).append(',').append(s.openingBalance().toPlainString()).append(',').append(s.closingBalance().toPlainString()).append("\r\nReference,Settled at UTC,Type,Description,Signed movement\r\n");for(var m:s.movements())out.append(m.transactionId()).append(',').append(m.occurredAt()).append(',').append(csvCell(m.type())).append(',').append(csvCell(m.description())).append(',').append(m.amount().toPlainString()).append("\r\n");return out.toString();}
    public byte[] pdf(Statement s){List<String> lines=new ArrayList<>(List.of("LedgerGuard | Account statement",s.accountNumber()+" | "+s.currency(),"UTC date range: "+s.from()+" to "+s.to(),"Verified coverage begins: "+s.coverageStart(),"Basis: "+s.provenance()+" (no inferred earlier balances)","Opening balance: "+s.openingBalance().toPlainString()+" "+s.currency(),"Closing balance: "+s.closingBalance().toPlainString()+" "+s.currency(),"","Reference | Settled at UTC | Type | Signed movement"));for(var m:s.movements()){lines.add("#"+m.transactionId()+" | "+m.occurredAt()+" | "+m.type()+" | "+m.amount().toPlainString());String description=m.description().replaceAll("[\\r\\n\\t]"," ");for(int i=0;i<description.length();i+=90)lines.add("  "+description.substring(i,Math.min(i+90,description.length())));}return SimplePdf.render(lines);}
}
