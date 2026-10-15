package com.tiendaposs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TiendaPossApplication {
    public static void main(String[] args) {
        SpringApplication.run(TiendaPossApplication.class, args);
    }
}
