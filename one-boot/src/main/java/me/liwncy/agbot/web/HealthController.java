package me.liwncy.agbot.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 探活。Docker / 扫描打到 {@code /} 时不要当静态资源 404。
 */
@RestController
public class HealthController {

    @GetMapping({"/", "/health"})
    public Map<String, String> ok() {
        return Map.of("status", "ok");
    }
}
