package com.trustagent.core.search;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class SearchConfiguration {

    @Bean
    ElasticsearchClient elasticsearchClient(SearchProperties properties, ObjectMapper mapper) {
        return new ElasticsearchClient(properties.baseUrl(), properties.username() == null ? "" : properties.username(),
                properties.password() == null ? "" : properties.password(), properties.timeout(), mapper);
    }

    @Bean
    SearchReindexService searchReindexService(JdbcClient jdbc, ElasticsearchClient client, SearchProperties properties, ObjectMapper mapper, Clock clock) {
        return new SearchReindexService(jdbc, client, properties, mapper, clock);
    }
}
