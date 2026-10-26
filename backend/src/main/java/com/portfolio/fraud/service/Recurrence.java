package com.portfolio.fraud.service;

import java.time.*;

/** Each date derives from the original anchor; February never shifts March's day. */
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
