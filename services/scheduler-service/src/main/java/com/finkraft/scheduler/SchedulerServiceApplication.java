package com.finkraft.scheduler;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class SchedulerServiceApplication {

    /** Config file is {@code scheduler-service.yml} so that every service can share one classpath in scenario tests. */
    public static final String CONFIG_NAME = "scheduler-service";

    public static void main(String[] args) {
        new SpringApplicationBuilder(SchedulerServiceApplication.class)
                .properties("spring.config.name=" + CONFIG_NAME)
                .run(args);
    }
}
