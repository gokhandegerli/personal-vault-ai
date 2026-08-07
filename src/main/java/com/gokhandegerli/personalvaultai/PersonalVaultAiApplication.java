package com.gokhandegerli.personalvaultai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PersonalVaultAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(PersonalVaultAiApplication.class, args);
    }
}
