package com.portfolio.fraud.service;

import java.time.*;

/**
 * Calculates each occurrence from the original local date/time in the chosen zone.
 * Month-end and leap-day clamping therefore do not shift later occurrences. Java's
 * zone rules resolve daylight-saving gaps and overlaps before returning a UTC instant.
 */
public final class Recurrence {
    private Recurrence() {}
    public enum Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }
    public static Instant at(Instant first,ZoneId zone,Frequency frequency,int index) {
        if(index<0)throw new IllegalArgumentException("Occurrence index must be nonnegative");
        var anchor=first.atZone(zone);
        return switch(frequency) {
            case DAILY -> anchor.plusDays(index).toInstant();
            case WEEKLY -> anchor.plusWeeks(index).toInstant();
            case MONTHLY -> anchor.plusMonths(index).toInstant();
            case YEARLY -> anchor.plusYears(index).toInstant();
        };
    }
}
