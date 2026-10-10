package com.portfolio.fraud.exception;
import org.springframework.http.HttpStatus;
// These failures must commit the failed-attempt counter, but never money changes.
public class ConfirmationDenied extends ApiException {
    public ConfirmationDenied(boolean limited) {
        super(limited?HttpStatus.TOO_MANY_REQUESTS:HttpStatus.BAD_REQUEST,
            limited?"CONFIRMATION_RATE_LIMITED":"INCORRECT_PASSWORD",
            limited?"Too many failed confirmation attempts. Please wait before trying again."
                :"The password is incorrect. No funds have moved.");
    }
}
