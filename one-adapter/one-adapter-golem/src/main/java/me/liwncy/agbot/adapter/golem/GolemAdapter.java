package me.liwncy.agbot.adapter.golem;

import me.liwncy.agbot.adapter.golem.api.GolemApiClient;
import me.liwncy.agbot.kernel.api.adapter.AdapterContext;
import me.liwncy.agbot.kernel.api.adapter.ChannelCapabilities;
import me.liwncy.agbot.kernel.api.adapter.ChatAdapter;
import me.liwncy.agbot.kernel.api.message.ChannelExtraKeys;
import me.liwncy.agbot.kernel.api.message.MsgType;
import me.liwncy.agbot.kernel.api.message.ReplyInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Golem 适配器：按通道契约上限映射已支持的 OpenAPI；门禁策略不在此层定义。
 */
@Component
@ConditionalOnProperty(prefix = "agbot.adapter.golem", name = "enabled", havingValue = "true")
public class GolemAdapter implements ChatAdapter {
    public static final String PLATFORM = "golem";
    private static final Logger log = LoggerFactory.getLogger(GolemAdapter.class);

    private static final ChannelCapabilities CAPABILITIES = ChannelCapabilities.builder()
            .inboundTypes(Set.of(
                    MsgType.TEXT, MsgType.IMAGE, MsgType.VIDEO, MsgType.AUDIO,
                    MsgType.FILE, MsgType.EMOJI, MsgType.LINK, MsgType.CARD,
                    MsgType.APP, MsgType.POSITION, MsgType.FORWARD
            ))
            .outboundTypes(Set.of(
                    MsgType.TEXT, MsgType.IMAGE, MsgType.VIDEO, MsgType.AUDIO,
                    MsgType.EMOJI, MsgType.LINK, MsgType.CARD, MsgType.APP,
                    MsgType.POSITION, MsgType.FORWARD
            ))
            .revoke(true)
            .quote(true)
            .remind(true)
            .build();

    private final GolemApiClient apiClient;

    public GolemAdapter(GolemApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public void init(AdapterContext ctx) {
        // no-op
    }

    @Override
    public void start() {
        log.info("Golem adapter started capabilities={}", CAPABILITIES);
    }

    @Override
    public void stop() {
        log.info("Golem adapter stopped");
    }

    @Override
    public ChannelCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public CompletableFuture<String> reply(ReplyInfo replyInfo) {
        return CompletableFuture.supplyAsync(() -> send(replyInfo));
    }

    @Override
    public CompletableFuture<Void> delMsg(List<String> msgIds) {
        return CompletableFuture.runAsync(() -> {
            if (msgIds == null) {
                return;
            }
            for (String id : msgIds) {
                try {
                    apiClient.revoke(id);
                } catch (Exception e) {
                    log.warn("Golem revoke failed msgId={}: {}", id, e.getMessage());
                }
            }
        });
    }

    @Override
    public Map<String, Object> bridge() {
        // 登录/通讯录等平台 API 后续按需挂载；消息收发优先走 ReplyInfo 类型映射
        return Map.of("platform", PLATFORM, "messaging", "use ReplyInfo types");
    }

    private String send(ReplyInfo replyInfo) {
        if (replyInfo == null) {
            return "";
        }
        String type = MsgType.normalize(replyInfo.type());
        if (!CAPABILITIES.supportsOutbound(type)) {
            log.warn("Golem unsupported outbound type={}, skip", type);
            return "";
        }
        String receiver = resolveReceiver(replyInfo);
        if (receiver == null || receiver.isBlank()) {
            throw new IllegalStateException("Golem reply missing receiver");
        }
        Map<String, Object> extra = replyInfo.extra() == null ? Map.of() : replyInfo.extra();
        String path = firstNonBlank(replyInfo.path(), replyInfo.msg());
        String msgId = switch (type) {
            case MsgType.TEXT -> sendTextMaybeAsMedia(receiver, replyInfo.msg(), replyInfo.remind());
            case MsgType.IMAGE -> sendImageOrFallback(receiver, path);
            case MsgType.VIDEO -> apiClient.sendVideo(
                    receiver, path,
                    stringExtra(extra, ChannelExtraKeys.THUMB),
                    stringExtra(extra, ChannelExtraKeys.DURATION));
            case MsgType.AUDIO -> apiClient.sendVoice(
                    receiver, path,
                    stringExtra(extra, ChannelExtraKeys.DURATION),
                    stringExtra(extra, ChannelExtraKeys.FORMAT));
            case MsgType.EMOJI -> apiClient.sendEmoji(
                    receiver,
                    stringExtra(extra, ChannelExtraKeys.MD5),
                    firstNonBlank(replyInfo.path(), replyInfo.msg()));
            case MsgType.LINK -> apiClient.sendLink(
                    receiver,
                    replyInfo.title(),
                    replyInfo.msg(),
                    firstNonBlank(replyInfo.url(), replyInfo.path()),
                    stringExtra(extra, ChannelExtraKeys.THUMB));
            case MsgType.CARD -> apiClient.sendCard(
                    receiver,
                    firstNonBlank(stringExtra(extra, ChannelExtraKeys.CARD_USERNAME), replyInfo.msg()),
                    stringExtra(extra, ChannelExtraKeys.CARD_NICKNAME),
                    stringExtra(extra, ChannelExtraKeys.CARD_ALIAS));
            case MsgType.APP -> apiClient.sendApp(
                    receiver,
                    intExtra(extra, ChannelExtraKeys.APP_TYPE, 1),
                    replyInfo.msg());
            case MsgType.POSITION -> apiClient.sendPosition(
                    receiver,
                    firstNonBlank(stringExtra(extra, ChannelExtraKeys.LABEL), replyInfo.msg()),
                    doubleExtra(extra, ChannelExtraKeys.LAT),
                    doubleExtra(extra, ChannelExtraKeys.LON),
                    stringExtra(extra, ChannelExtraKeys.POI_NAME),
                    intExtra(extra, ChannelExtraKeys.SCALE, 15));
            case MsgType.FORWARD -> apiClient.sendForward(
                    receiver,
                    firstNonBlank(stringExtra(extra, ChannelExtraKeys.FORWARD_TYPE), "image"),
                    replyInfo.msg());
            default -> {
                log.warn("Golem no mapping for type={}", type);
                yield "";
            }
        };
        log.debug("Golem sent type={} receiver={} msgId={}", type, receiver, msgId);
        return msgId;
    }

    private String sendImageOrFallback(String receiver, String imageUrl) {
        try {
            return apiClient.sendImage(receiver, imageUrl);
        } catch (Exception e) {
            log.warn("Golem sendImage failed url={} err={}", preview(imageUrl), e.toString());
            return apiClient.sendText(receiver, imageUrl == null ? "" : imageUrl, null);
        }
    }

    /**
     * Agent 常把媒体结果以文字 URL 返回；识别后改走图片/视频消息，避免微信文本截断长链。
     */
    private String sendTextMaybeAsMedia(String receiver, String content, String remind) {
        OutboundImageLinks.Split images = OutboundImageLinks.split(content);
        OutboundVideoLinks.Split videos = OutboundVideoLinks.split(
                images.hasImages() ? images.remainingText() : content);
        if (!images.hasImages() && !videos.hasVideos()) {
            return apiClient.sendText(receiver, content, remind);
        }

        String lastMsgId = "";
        String caption = videos.hasVideos() ? videos.remainingText() : images.remainingText();
        if (caption != null && !caption.isBlank()) {
            lastMsgId = apiClient.sendText(receiver, caption, remind);
        }
        if (images.hasImages()) {
            for (String imageUrl : images.imageUrls()) {
                try {
                    lastMsgId = apiClient.sendImage(receiver, imageUrl);
                } catch (Exception e) {
                    log.warn("Golem sendImage failed url={} err={}", preview(imageUrl), e.toString());
                    lastMsgId = apiClient.sendText(receiver, imageUrl, null);
                }
            }
        }
        if (videos.hasVideos()) {
            for (String videoUrl : videos.videoUrls()) {
                try {
                    lastMsgId = apiClient.sendVideo(receiver, videoUrl, null, null);
                } catch (Exception e) {
                    log.warn("Golem sendVideo failed url={} err={}", preview(videoUrl), e.toString());
                    // 勿再走文本媒体识别，否则会二次 sendVideo；降级为链接卡片
                    lastMsgId = apiClient.sendLink(receiver, "视频", "点开看看", videoUrl, null);
                }
            }
        }
        return lastMsgId;
    }

    private static String preview(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 120 ? value : value.substring(0, 117) + "...";
    }

    private static String resolveReceiver(ReplyInfo replyInfo) {
        String groupId = replyInfo.groupId();
        if (groupId != null && !groupId.isBlank() && !"0".equals(groupId)) {
            return groupId.contains("@chatroom") ? groupId : groupId + "@chatroom";
        }
        return replyInfo.userId();
    }

    private static String stringExtra(Map<String, Object> extra, String key) {
        Object v = extra.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static int intExtra(Map<String, Object> extra, String key, int def) {
        Object v = extra.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(String.valueOf(v));
            } catch (Exception ignored) {
                // fall through
            }
        }
        return def;
    }

    private static double doubleExtra(Map<String, Object> extra, String key) {
        Object v = extra.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v != null) {
            try {
                return Double.parseDouble(String.valueOf(v));
            } catch (Exception ignored) {
                // fall through
            }
        }
        return 0D;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
