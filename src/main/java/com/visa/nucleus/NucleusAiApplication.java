package com.visa.nucleus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NucleusAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(NucleusAiApplication.class, args);
    }
}
