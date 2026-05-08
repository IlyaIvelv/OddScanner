package com.oddscanner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OddScannerApplication {
    public static void main(String[] args) {
        SpringApplication.run(OddScannerApplication.class, args);
    }
}
