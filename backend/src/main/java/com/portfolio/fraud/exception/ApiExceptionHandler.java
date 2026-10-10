package com.portfolio.fraud.exception;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {
    record Error(Instant timestamp,int status,String error,String message,String path) {}
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Error> handle(ApiException e,HttpServletRequest r) {
        return ResponseEntity.status(e.status).body(new Error(Instant.now(),e.status.value(),e.code,e.getMessage(),r.getRequestURI()));
    }
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    ResponseEntity<Error> validation(org.springframework.web.bind.MethodArgumentNotValidException e,HttpServletRequest r) {
        String message=e.getBindingResult().getFieldErrors().stream()
            .map(f->f.getField()+": "+f.getDefaultMessage()).distinct().sorted().collect(java.util.stream.Collectors.joining("; "));
        return ResponseEntity.badRequest().body(new Error(Instant.now(),400,"VALIDATION_ERROR",message,r.getRequestURI()));
    }
    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<Error> malformed(HttpServletRequest r) {
        return ResponseEntity.badRequest().body(new Error(Instant.now(),400,"INVALID_REQUEST","Check the request fields and filter values",r.getRequestURI()));
    }
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    ResponseEntity<Error> conflict(HttpServletRequest r) {
        return ResponseEntity.status(409).body(new Error(Instant.now(),409,"DATA_CONFLICT","The record conflicts with existing data or a storage limit",r.getRequestURI()));
    }
}
