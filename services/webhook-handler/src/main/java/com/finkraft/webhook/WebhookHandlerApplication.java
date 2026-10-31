package com.finkraft.webhook;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class WebhookHandlerApplication {

    /** Config file is {@code webhook-handler.yml} so that every service can share one classpath in scenario tests. */
    public static final String CONFIG_NAME = "webhook-handler";

    public static void main(String[] args) {
        new SpringApplicationBuilder(WebhookHandlerApplication.class)
                .properties("spring.config.name=" + CONFIG_NAME)
                .run(args);
    }
}
