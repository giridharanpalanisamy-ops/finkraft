package com.finkraft.management;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class ManagementServiceApplication {

    /** Config file is {@code management-service.yml} so that every service can share one classpath in scenario tests. */
    public static final String CONFIG_NAME = "management-service";

    public static void main(String[] args) {
        new SpringApplicationBuilder(ManagementServiceApplication.class)
                .properties("spring.config.name=" + CONFIG_NAME)
                .run(args);
    }
}
