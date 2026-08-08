package me.liwncy.agbot.common.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通道入站/出站日志。
 */
public final class ChannelLog {
    private static final Logger log = LoggerFactory.getLogger("agbot.channel");

    private ChannelLog() {
    }

    public static void inbound(String platform, String accountId, String summary) {
        log.info("IN  platform={} accountId={} {}", platform, accountId, summary);
    }

    public static void outbound(String platform, String accountId, String summary) {
        log.info("OUT platform={} accountId={} {}", platform, accountId, summary);
    }
}
