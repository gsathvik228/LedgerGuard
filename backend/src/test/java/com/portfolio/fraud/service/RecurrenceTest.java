package com.portfolio.fraud.service;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.portfolio.fraud.service.Recurrence.Frequency.*;

class RecurrenceTest {
    @Test void monthEndClampsWithoutDriftingTheOriginalAnchor(){
        var first=Instant.parse("2027-01-31T10:00:00Z");
        assertThat(Recurrence.at(first,ZoneOffset.UTC,MONTHLY,1)).isEqualTo(Instant.parse("2027-02-28T10:00:00Z"));
        assertThat(Recurrence.at(first,ZoneOffset.UTC,MONTHLY,2)).isEqualTo(Instant.parse("2027-03-31T10:00:00Z"));
    }
    @Test void leapYearAnchorReturnsOnTheNextLeapYear(){
        var first=Instant.parse("2028-02-29T10:00:00Z");
        assertThat(Recurrence.at(first,ZoneOffset.UTC,YEARLY,1)).isEqualTo(Instant.parse("2029-02-28T10:00:00Z"));
        assertThat(Recurrence.at(first,ZoneOffset.UTC,YEARLY,4)).isEqualTo(Instant.parse("2032-02-29T10:00:00Z"));
    }
    @Test void wallClockTimeSurvivesDaylightSavingBoundary(){
        var zone=ZoneId.of("America/New_York");var first=ZonedDateTime.of(2026,3,7,9,0,0,0,zone).toInstant();
        var second=Recurrence.at(first,zone,DAILY,1);
        assertThat(second.atZone(zone).getHour()).isEqualTo(9);
        assertThat(Duration.between(first,second)).isEqualTo(Duration.ofHours(23));
        assertThat(Recurrence.at(first,zone,WEEKLY,1).atZone(zone).getHour()).isEqualTo(9);
    }
    @Test void nonexistentLocalTimeMovesForwardWithoutShiftingLaterOccurrences(){
        var zone=ZoneId.of("America/New_York");var first=ZonedDateTime.of(2026,3,7,2,30,0,0,zone).toInstant();
        assertThat(Recurrence.at(first,zone,DAILY,1).atZone(zone).getHour()).isEqualTo(3);
        assertThat(Recurrence.at(first,zone,DAILY,2).atZone(zone).getHour()).isEqualTo(2);
        assertThatThrownBy(()->Recurrence.at(first,zone,DAILY,-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
