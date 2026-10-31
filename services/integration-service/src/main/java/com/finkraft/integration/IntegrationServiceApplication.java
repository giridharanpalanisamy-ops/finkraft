package com.finkraft.integration;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class IntegrationServiceApplication {

    /** Config file is {@code integration-service.yml} so that every service can share one classpath in scenario tests. */
    public static final String CONFIG_NAME = "integration-service";

    public static void main(String[] args) {
        new SpringApplicationBuilder(IntegrationServiceApplication.class)
                .properties("spring.config.name=" + CONFIG_NAME)
                .run(args);
    }
}
