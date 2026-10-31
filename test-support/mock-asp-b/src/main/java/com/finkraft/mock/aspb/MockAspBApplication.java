package com.finkraft.mock.aspb;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class MockAspBApplication {

    /** Config file is {@code mock-asp-b.yml} so that every service can share one classpath in scenario tests. */
    public static final String CONFIG_NAME = "mock-asp-b";

    public static void main(String[] args) {
        new SpringApplicationBuilder(MockAspBApplication.class)
                .properties("spring.config.name=" + CONFIG_NAME)
                .run(args);
    }
}
