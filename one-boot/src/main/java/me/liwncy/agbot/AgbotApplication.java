package me.liwncy.agbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "me.liwncy.agbot")
public class AgbotApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgbotApplication.class, args);
    }
}
