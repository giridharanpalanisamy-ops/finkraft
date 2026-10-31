package com.finkraft.management;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(ManagementProperties.class)
public class ManagementConfig {

    @Bean
    RestClient invoiceHandlerClient(ManagementProperties props) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().baseUrl(props.invoiceHandler().url()).requestFactory(factory).build();
    }
}
