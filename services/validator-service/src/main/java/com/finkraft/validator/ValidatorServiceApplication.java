package com.finkraft.validator;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class ValidatorServiceApplication {

    /** Config file is {@code validator-service.yml} so that every service can share one classpath in scenario tests. */
    public static final String CONFIG_NAME = "validator-service";

    public static void main(String[] args) {
        new SpringApplicationBuilder(ValidatorServiceApplication.class)
                .properties("spring.config.name=" + CONFIG_NAME)
                .run(args);
    }
}
