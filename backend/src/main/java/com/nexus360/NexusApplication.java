package com.nexus360;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableAsync
public class NexusApplication {

    /** API release reported by the health endpoints. */
    public static final String VERSION = "2.0.0";

    public static void main(String[] args) {
        SpringApplication.run(NexusApplication.class, args);
    }
}
