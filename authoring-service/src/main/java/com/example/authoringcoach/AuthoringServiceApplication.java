package com.example.authoringcoach;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AuthoringServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthoringServiceApplication.class, args);
    }
}
