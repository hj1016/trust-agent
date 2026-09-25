package com.trustagent.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TrustAgentCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrustAgentCoreApplication.class, args);
    }
}
