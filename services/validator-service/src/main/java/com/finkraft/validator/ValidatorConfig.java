package com.finkraft.validator;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ValidatorConfig {

    /** "Today" for the issue-date rule is today in the UAE. */
    @Bean
    Clock uaeClock() {
        return Clock.system(ZoneId.of("Asia/Dubai"));
    }
}
