package com.portfolio.fraud.security;

import java.math.BigDecimal;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class MoneyJsonConfig {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer exactMoney() {
        // Preserve decimal precision all the way to the browser.
        return builder -> builder.serializerByType(BigDecimal.class, ToStringSerializer.instance);
    }
}
