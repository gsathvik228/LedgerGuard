package com.portfolio.fraud.controller;

import com.portfolio.fraud.entity.*;
import com.portfolio.fraud.service.*;
import com.portfolio.fraud.dto.TransactionDtos.TransactionResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api")
public class WorkspaceController {
    private final WorkspaceService workspace;private final ReviewService reviews;private final TransactionService transactions;private final IdempotencyService keys;
    public WorkspaceController(WorkspaceService w,ReviewService r,TransactionService t,IdempotencyService k){workspace=w;reviews=r;transactions=t;keys=k;}
    public record Decision(@NotNull Boolean approve,@NotBlank @Size(max=180) String reason){}
    public record Beneficiary(@NotBlank @Size(max=34) String accountNumber,@NotBlank @Size(max=80) String friendlyName){}
    public record Rename(@NotBlank @Size(max=80) String friendlyName){}
    @GetMapping("/admin/reviews") public Object queue(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return transactions.monitoring(null,TransactionStatus.AWAITING_REVIEW,null,null,null,page,size,false);}
    @PostMapping("/admin/reviews/{id}/decision") public TransactionResponse decision(@AuthenticationPrincipal User u,@PathVariable Long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@Valid @RequestBody Decision d){return keys.execute(u,"review:"+id,key,d,()->reviews.decide(u,id,d.approve(),d.reason()));}
    @GetMapping("/transactions/{id}/timeline") public Object timeline(@AuthenticationPrincipal User u,@PathVariable Long id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return workspace.timeline(u,id,page,size);}
    @GetMapping("/admin/audit") public Object audit(@RequestParam(required=false)Long actorId,@RequestParam(required=false)Long transactionId,@RequestParam(required=false)String action,@RequestParam(required=false)Instant from,@RequestParam(required=false)Instant to,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return workspace.audit(actorId,transactionId,action,from,to,page,size);}
    @GetMapping("/notifications") public Object notifications(@AuthenticationPrincipal User u,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return workspace.notifications(u,page,size);}
    @GetMapping("/notifications/unread") public Object unread(@AuthenticationPrincipal User u){return Map.of("count",workspace.unread(u));}
    @PostMapping("/notifications/{id}/read") public void read(@AuthenticationPrincipal User u,@PathVariable Long id){workspace.read(u,id);}
    @GetMapping("/beneficiaries") public Object beneficiaries(@AuthenticationPrincipal User u){return workspace.beneficiaries(u);}
    @PostMapping("/beneficiaries") public void save(@AuthenticationPrincipal User u,@Valid @RequestBody Beneficiary b){workspace.saveBeneficiary(u,b.accountNumber(),b.friendlyName());}
    @PatchMapping("/beneficiaries/{id}") public void rename(@AuthenticationPrincipal User u,@PathVariable Long id,@Valid @RequestBody Rename r){workspace.rename(u,id,r.friendlyName());}
    @DeleteMapping("/beneficiaries/{id}") public void remove(@AuthenticationPrincipal User u,@PathVariable Long id){workspace.remove(u,id);}
}
