package com.cryptomind;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CryptoMindApplication {

    public static void main(String[] args) {
        SpringApplication.run(CryptoMindApplication.class, args);
    }

}
