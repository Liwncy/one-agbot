package me.liwncy.agbot.agent;

import me.liwncy.agbot.agent.config.AgbotAgentProperties;
import me.liwncy.agbot.kernel.api.agent.AgentBridge;
import me.liwncy.agbot.kernel.api.agent.AgentOutcome;
import me.liwncy.agbot.kernel.api.message.MsgInfo;
import me.liwncy.agbot.kernel.api.message.ReplyInfo;
import me.liwncy.agbot.kernel.api.session.ConversationMapper;
import me.liwncy.agbot.kernel.api.session.SessionKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 默认 AgentBridge：OpenAPI sync chat（图片走 resource upload + attachments）。
 */
public class SnailAiAgentBridge implements AgentBridge {
    /** 与 agbot.channel 同风格，IDEA / logs 里搜 agbot.agent 即可看到对话正文。 */
    private static final Logger log = LoggerFactory.getLogger("agbot.agent");

    private final SnailAiOpenApiClient client;
    private final ConversationMapper conversationMapper;
    private final AgbotAgentProperties properties;

    public SnailAiAgentBridge(SnailAiOpenApiClient client,
                              ConversationMapper conversationMapper,
                              AgbotAgentProperties properties) {
        this.client = client;
        this.conversationMapper = conversationMapper;
        this.properties = properties;
    }

    @Override
    public CompletableFuture<AgentOutcome> handle(MsgInfo msgInfo) {
        if (properties.isAsyncHandled()) {
            CompletableFuture.runAsync(() -> {
                try {
                    doHandle(msgInfo);
                } catch (Exception e) {
                    log.error("Async agent handle failed", e);
                }
            });
            return CompletableFuture.completedFuture(new AgentOutcome.Handled("async"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return doHandle(msgInfo);
            } catch (Exception e) {
                String reply = AgentUserReply.fromThrowable(e);
                log.warn("Agent handle failed, reply friendly userId={} reply={} err={}",
                        msgInfo.userId(), reply, e.toString());
                return new AgentOutcome.Reply(ReplyInfo.text(reply, msgInfo));
            }
        });
    }

    private AgentOutcome doHandle(MsgInfo msgInfo) {
        String externalId = SessionKeys.externalUserId(msgInfo);
        String openId = client.ensureOpenId(externalId, msgInfo.userName());
        String conversationId = conversationMapper.resolveConversationId(SessionKeys.of(msgInfo));
        AgentUserInput input = AgentMessageFormatter.from(msgInfo);

        List<Long> imageIds = new ArrayList<>();
        AgentMediaLoader.LoadedImage image = AgentMediaLoader.loadImageAttachment(input.msgType(), input.media());
        if (image != null) {
            try {
                long resourceId = client.uploadImage(openId, image.fileName(), image.bytes());
                imageIds.add(resourceId);
            } catch (Exception e) {
                log.warn("Upload image attachment failed, fallback text-only: {}", e.getMessage());
            }
        } else if (input.hasMedia()) {
            log.info("Agent media not uploaded type={} form={} usable={}",
                    input.msgType(),
                    input.media().form(),
                    input.hasUsableMedia());
        }

        String content = imageIds.isEmpty()
                ? AgentMessageFormatter.toUserMessage(msgInfo)
                : firstNonBlank(input.content(), AgentMessageFormatter.withSpeaker(msgInfo, "请看这张图片"));
        log.info("Agent chat openId={} conversationId={} userId={} userName={} groupId={} msgType={} attachments={} media={} content={}",
                openId, conversationId, msgInfo.userId(), msgInfo.userName(), msgInfo.groupId(),
                input.msgType(), imageIds.size(), mediaSummary(input), preview(content));
        String answer;
        try {
            String raw = client.chatSync(
                    properties.getDefaultAgentId(),
                    openId,
                    conversationId,
                    content,
                    imageIds
            );
            answer = AgentUserReply.fromAnswer(raw);
            if (!answer.equals(raw == null ? "" : raw)) {
                log.warn("Agent raw answer sanitized openId={} conversationId={} raw={}",
                        openId, conversationId, preview(raw));
            }
        } catch (Exception e) {
            answer = AgentUserReply.fromThrowable(e);
            log.warn("Agent chat failed, reply friendly openId={} conversationId={} reply={} err={}",
                    openId, conversationId, answer, e.toString());
        }
        log.info("Agent reply openId={} conversationId={} userId={} userName={} answer={}",
                openId, conversationId, msgInfo.userId(), msgInfo.userName(), preview(answer));
        return new AgentOutcome.Reply(ReplyInfo.text(answer, msgInfo));
    }

    private static String mediaSummary(AgentUserInput input) {
        if (input == null || input.media() == null) {
            return "-";
        }
        var media = input.media();
        return switch (media.form()) {
            case URL, FILE -> media.form() + "(" + preview(media.path(), 120) + ")";
            case BASE64 -> media.form() + "(len=" + (media.base64() == null ? 0 : media.base64().length())
                    + ", mime=" + media.mime() + ")";
            case PLATFORM -> media.form() + "(" + preview(media.platformId(), 120) + ", usable=false)";
        };
    }

    private static String preview(String text) {
        return preview(text, 200);
    }

    private static String preview(String text, int max) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').trim();
        int limit = Math.max(16, max);
        return oneLine.length() <= limit ? oneLine : oneLine.substring(0, limit) + "...";
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
