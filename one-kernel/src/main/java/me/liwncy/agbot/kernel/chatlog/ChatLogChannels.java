package me.liwncy.agbot.kernel.chatlog;

/**
 * 适配器 platform() → 落库通道名。Golem 路由键是 golem，通道是 wechat。
 */
public final class ChatLogChannels {

    private ChatLogChannels() {
    }

    public static String channelPlatform(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            return "unknown";
        }
        return switch (adapterId) {
            case "golem" -> "wechat";
            default -> adapterId;
        };
    }
}
