package com.finkraft.adapters;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Builds the adapters for services that talk to ASPs (finkraft.asp.adapters-enabled=true). */
@AutoConfiguration
@ConditionalOnProperty(name = "finkraft.asp.adapters-enabled", havingValue = "true")
@EnableConfigurationProperties(AspAdapterProperties.class)
public class AspAdaptersAutoConfiguration {

    @Bean
    public AspAdapters aspAdapters(AspAdapterProperties props) {
        return new AspAdapters(List.of(new AspAAdapter(props), new AspBAdapter(props)));
    }
}
