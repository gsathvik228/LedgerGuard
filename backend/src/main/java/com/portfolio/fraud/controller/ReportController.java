package com.portfolio.fraud.controller;
import com.portfolio.fraud.entity.User;
import com.portfolio.fraud.service.ReportService;
import com.portfolio.fraud.exception.ApiException;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api")
public class ReportController {
    private final ReportService reports;public ReportController(ReportService r){reports=r;}
    @GetMapping("/statements/coverage")public Object coverage(@AuthenticationPrincipal User u){return reports.coverage(u);}
    @GetMapping("/accounts/{id}/statement")public Object statement(@AuthenticationPrincipal User u,@PathVariable Long id,@RequestParam LocalDate from,@RequestParam LocalDate to,@RequestParam(defaultValue="json")String format){
        var statement=reports.statement(u,id,from,to);if(format.equals("json"))return statement;
        if(!format.equals("csv")&&!format.equals("pdf"))throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_FORMAT","Choose json, csv or pdf.");
        byte[] body=format.equals("pdf")?reports.pdf(statement):reports.csv(statement).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=ledgerguard-"+id+"-"+from+"."+format).contentType(MediaType.parseMediaType(format.equals("pdf")?"application/pdf":"text/csv;charset=UTF-8")).body(body);
    }
    @GetMapping("/insights")public Object insights(@AuthenticationPrincipal User u,@RequestParam(required=false)Long accountId,@RequestParam(required=false)String currency,@RequestParam LocalDate from,@RequestParam LocalDate to){return reports.insights(u,accountId,currency,from,to);}
}
