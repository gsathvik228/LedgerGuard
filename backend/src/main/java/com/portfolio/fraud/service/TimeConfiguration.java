package com.portfolio.fraud.service;

import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration
public class TimeConfiguration {
    @Bean public Clock applicationClock(){return Clock.systemUTC();}
}
