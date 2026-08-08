package me.liwncy.agbot.snailai;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Snail AI Server 启动程序（委托官方 starter）。
 */
@SpringBootApplication
public class SnailAiServerApplication {

    public static void main(String[] args) {
        com.aizuda.snail.ai.starter.SnailAiApplication.main(args);
    }
}
