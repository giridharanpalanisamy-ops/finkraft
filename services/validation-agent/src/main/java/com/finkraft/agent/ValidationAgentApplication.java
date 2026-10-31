package com.finkraft.agent;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class ValidationAgentApplication {

    /** Config file is {@code validation-agent.yml} so that every service can share one classpath in scenario tests. */
    public static final String CONFIG_NAME = "validation-agent";

    public static void main(String[] args) {
        new SpringApplicationBuilder(ValidationAgentApplication.class)
                .properties("spring.config.name=" + CONFIG_NAME)
                .run(args);
    }
}
