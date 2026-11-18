package com.shop.common.spring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CommonLibraryStarter {

    public static void main(final String[] args) {
        SpringApplication.run(CommonLibraryStarter.class, args);
    }
}
