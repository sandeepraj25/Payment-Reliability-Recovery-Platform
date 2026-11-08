package com.payguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PayGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayGuardApplication.class, args);
    }
}
