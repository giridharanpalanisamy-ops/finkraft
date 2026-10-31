package com.finkraft.handler.config;

import com.finkraft.statemachine.InvoiceStateMachine;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(HandlerProperties.class)
public class HandlerConfig {

    @Bean
    InvoiceStateMachine invoiceStateMachine() {
        return new InvoiceStateMachine();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
