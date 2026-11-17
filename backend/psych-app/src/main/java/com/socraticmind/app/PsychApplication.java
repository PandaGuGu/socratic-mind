package com.socraticmind.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(scanBasePackages = "com.socraticmind",
        exclude = DataSourceAutoConfiguration.class)
public class PsychApplication {

    public static void main(String[] args) {
        SpringApplication.run(PsychApplication.class, args);
    }
}
