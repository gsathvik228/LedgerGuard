package com.portfolio.fraud.service;

import com.portfolio.fraud.dto.PaymentRequestDtos.*;
import com.portfolio.fraud.dto.TransactionDtos.*;
import com.portfolio.fraud.entity.*;
import com.portfolio.fraud.exception.ApiException;
import com.portfolio.fraud.repository.*;
import java.time.Clock;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.portfolio.fraud.service.ResourceEvents.Type.PAYMENT_REQUEST;

@Service
public class PaymentRequestService {
    private final PaymentRequestRepository requests;
    private final UserRepository users;
    private final AccountRepository accounts;
    private final TransactionService transactions;
    private final JdbcTemplate db;
    private final ResourceEvents events;
    private final Clock clock;

    public PaymentRequestService(PaymentRequestRepository requests,UserRepository users,AccountRepository accounts,
        TransactionService transactions,JdbcTemplate db,ResourceEvents events,Clock clock) {
        this.requests=requests;this.users=users;this.accounts=accounts;this.transactions=transactions;
        this.db=db;this.events=events;this.clock=clock;
    }
    @Transactional
    public View create(User actor,Create input) {
        customer(actor);
        User recipient=users.findByEmail(input.recipientEmail().strip().toLowerCase(Locale.ROOT))
            .filter(u->u.getRole()==Role.CUSTOMER).orElseThrow(()->bad("No registered customer matches that email."));
        if(recipient.getId().equals(actor.getId()))throw bad("You cannot request money from yourself.");
        Account receiving=accounts.findById(input.receivingAccountId()).orElseThrow(()->bad("Choose an active receiving account you own."));
        if(!receiving.getUser().getId().equals(actor.getId())||receiving.getStatus()!=AccountStatus.ACTIVE)
            throw bad("Choose an active receiving account you own.");
        if(input.amount()==null||input.amount().signum()<=0||input.amount().scale()>2||input.amount().precision()-input.amount().scale()>17)
            throw bad("Enter a positive amount with at most two decimal places.");
        if(input.expiresAt()!=null&&!input.expiresAt().isAfter(clock.instant()))throw bad("Expiry must be in the future.");
        PaymentRequest request=requests.saveAndFlush(new PaymentRequest(actor,recipient,receiving,input.amount(),input.description().strip(),input.expiresAt(),clock.instant()));
        events.audit(actor.getId(),"PAYMENT_REQUEST_CREATED",PAYMENT_REQUEST,request.getId(),null,"PENDING");
        events.notify(recipient.getId(),PAYMENT_REQUEST,request.getId(),"CREATED","You received a payment request. Review its amount and purpose before paying.");
        return view(actor,request);
    }
    @Transactional(readOnly=true)
    public Page<View> list(User actor,String direction,PaymentRequestStatus status,int page,int size) {
        if(!Set.of("sent","received").contains(direction))throw bad("Direction must be sent or received.");
        if(page<0||size<1||size>100)throw bad("Page must be nonnegative and size between 1 and 100.");
        return requests.search(actor.getId(),direction.equals("sent"),status,clock.instant(),PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"createdAt","id"))).map(r->view(actor,r));
    }
    @Transactional(readOnly=true)
    public Detail detail(User actor,Long id) {
        PaymentRequest r=visible(actor,requests.findById(id).orElseThrow(this::missing));
        var attempts=db.query("SELECT a.transaction_id,t.status,a.created_at FROM payment_request_attempts a JOIN transactions t ON t.id=a.transaction_id WHERE a.request_id=? ORDER BY a.id DESC",
            (rs,n)->new Attempt(rs.getLong(1),rs.getString(2),rs.getTimestamp(3).toInstant()),id);
        return new Detail(view(actor,r),attempts);
    }
    /** Called only inside IdempotencyService's transaction. Lock precedes all account locks. */
    @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=ApiException.class)
    public TransactionResponse pay(User actor,Long id,Pay input) {
        PaymentRequest r=visible(actor,requests.lock(id).orElseThrow(this::missing));
        if(!r.getRecipient().getId().equals(actor.getId()))throw forbidden();
        expire(r);
        requirePending(r);
        // The ordinary transfer service rechecks ownership, statuses, currency, funds and fraud under account locks.
        var tx=transactions.transfer(actor,new TransferRequest(input.senderAccountId(),r.getReceivingAccount().getId(),r.getAmount(),"Payment request #"+id));
        r.attach(tx.id(),TransactionStatus.valueOf(tx.status()),clock.instant());
        db.update("INSERT INTO payment_request_attempts(request_id,transaction_id) VALUES(?,?)",id,tx.id());
        events.audit(actor.getId(),"PAYMENT_REQUEST_PAYMENT",PAYMENT_REQUEST,id,"PENDING",r.getStatus().name());
        if(r.getStatus()==PaymentRequestStatus.PAID)resolved(r);
        return tx;
    }
    @Transactional(noRollbackFor=ApiException.class)
    public View decide(User actor,Long id,boolean cancel) {
        PaymentRequest r=visible(actor,requests.lock(id).orElseThrow(this::missing));
        if(!(cancel?r.getRequester():r.getRecipient()).getId().equals(actor.getId()))throw forbidden();
        expire(r);requirePending(r);
        r.resolve(cancel?PaymentRequestStatus.CANCELLED:PaymentRequestStatus.DECLINED,clock.instant());
        events.audit(actor.getId(),"PAYMENT_REQUEST_"+r.getStatus(),PAYMENT_REQUEST,id,"PENDING",r.getStatus().name());
        resolved(r);return view(actor,r);
    }
    public void expire(PaymentRequest r) {
        if(r.getStatus()==PaymentRequestStatus.PENDING&&r.deadlinePassed(clock.instant())) {
            r.resolve(PaymentRequestStatus.EXPIRED,clock.instant());
            events.audit(null,"PAYMENT_REQUEST_EXPIRED",PAYMENT_REQUEST,r.getId(),"PENDING","EXPIRED");resolved(r);
        }
    }
    private void resolved(PaymentRequest r) {
        events.notify(r.getRequester().getId(),PAYMENT_REQUEST,r.getId(),r.getStatus().name(),"Payment request #"+r.getId()+" is "+r.getStatus().name().toLowerCase(Locale.ROOT)+".");
        if(r.getStatus()==PaymentRequestStatus.CANCELLED||r.getStatus()==PaymentRequestStatus.EXPIRED)
            events.notify(r.getRecipient().getId(),PAYMENT_REQUEST,r.getId(),r.getStatus().name(),"Payment request #"+r.getId()+" is "+r.getStatus().name().toLowerCase(Locale.ROOT)+".");
    }
    private void requirePending(PaymentRequest r){if(r.getStatus()!=PaymentRequestStatus.PENDING)throw new ApiException(HttpStatus.CONFLICT,"REQUEST_NOT_PAYABLE","This request is no longer pending. Reload its current outcome.");}
    private PaymentRequest visible(User actor,PaymentRequest r){if(!r.getRequester().getId().equals(actor.getId())&&!r.getRecipient().getId().equals(actor.getId()))throw missing();return r;}
    private void customer(User actor){if(actor.getRole()!=Role.CUSTOMER)throw forbidden();}
    private View view(User actor,PaymentRequest r) {
        var status=r.getStatus()==PaymentRequestStatus.PENDING&&r.deadlinePassed(clock.instant())?PaymentRequestStatus.EXPIRED:r.getStatus();
        boolean open=status==PaymentRequestStatus.PENDING,recipient=r.getRecipient().getId().equals(actor.getId());
        return new View(r.getId(),r.getRequester().getFullName(),r.getRecipient().getFullName(),r.getAmount(),r.getCurrency(),r.getDescription(),status,r.getCreatedAt(),r.getUpdatedAt(),r.getExpiresAt(),r.getResolvedAt(),r.getTransactionId(),open&&recipient,open&&recipient,open&&!recipient);
    }
    private ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"REQUEST_NOT_FOUND","Payment request not found.");}
    private ApiException forbidden(){return new ApiException(HttpStatus.FORBIDDEN,"REQUEST_FORBIDDEN","You cannot perform this action on this request.");}
    private ApiException bad(String message){return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST",message);}
}
