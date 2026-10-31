package com.finkraft.statusmapper;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class StatusMapperApplication {

    /** Config file is {@code status-mapper.yml} so that every service can share one classpath in scenario tests. */
    public static final String CONFIG_NAME = "status-mapper";

    public static void main(String[] args) {
        new SpringApplicationBuilder(StatusMapperApplication.class)
                .properties("spring.config.name=" + CONFIG_NAME)
                .run(args);
    }
}
