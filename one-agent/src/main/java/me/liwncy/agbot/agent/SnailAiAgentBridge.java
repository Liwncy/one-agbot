package me.liwncy.agbot.agent;

import me.liwncy.agbot.agent.config.AgbotAgentProperties;
import me.liwncy.agbot.kernel.api.agent.AgentBridge;
import me.liwncy.agbot.kernel.api.agent.AgentOutcome;
import me.liwncy.agbot.kernel.api.message.MsgInfo;
import me.liwncy.agbot.kernel.api.message.ReplyInfo;
import me.liwncy.agbot.kernel.api.runtime.AdapterRuntime;
import me.liwncy.agbot.kernel.api.session.ConversationMapper;
import me.liwncy.agbot.kernel.api.session.SessionKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 默认 AgentBridge：优先 OpenAPI chatStream 分片推送；可回退 sync。
 */
public class SnailAiAgentBridge implements AgentBridge {
    /** 与 agbot.channel 同风格，IDEA / logs 里搜 agbot.agent 即可看到对话正文。 */
    private static final Logger log = LoggerFactory.getLogger("agbot.agent");

    private final SnailAiOpenApiClient client;
    private final ConversationMapper conversationMapper;
    private final AgbotAgentProperties properties;
    private final ObjectProvider<AdapterRuntime> runtimeProvider;

    public SnailAiAgentBridge(SnailAiOpenApiClient client,
                              ConversationMapper conversationMapper,
                              AgbotAgentProperties properties,
                              ObjectProvider<AdapterRuntime> runtimeProvider) {
        this.client = client;
        this.conversationMapper = conversationMapper;
        this.properties = properties;
        this.runtimeProvider = runtimeProvider;
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

        // 正文只用可读摘要；媒体失败时不要把 URL/form/platformId 塞进对话（模型会当成「一串代码」）
        String content = firstNonBlank(
                input.content(),
                AgentMessageFormatter.withSpeaker(msgInfo,
                        imageIds.isEmpty() ? "（附件没带上，按文字聊）" : "请看这张图片"));
        if (imageIds.isEmpty() && input.hasMedia()) {
            log.warn("Agent chat without attachment after media present type={} media={}",
                    input.msgType(), mediaSummary(input));
        }
        log.info("Agent chat openId={} conversationId={} userId={} userName={} groupId={} msgType={} attachments={} stream={} media={} content={}",
                openId, conversationId, msgInfo.userId(), msgInfo.userName(), msgInfo.groupId(),
                input.msgType(), imageIds.size(), properties.isStreamReply(), mediaSummary(input), preview(content));

        if (properties.isStreamReply()) {
            return handleStream(msgInfo, openId, conversationId, content, imageIds);
        }
        return handleSync(msgInfo, openId, conversationId, content, imageIds);
    }

    private AgentOutcome handleSync(MsgInfo msgInfo, String openId, String conversationId,
                                    String content, List<Long> imageIds) {
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

    private AgentOutcome handleStream(MsgInfo msgInfo, String openId, String conversationId,
                                      String content, List<Long> imageIds) {
        AdapterRuntime runtime = runtimeProvider.getIfAvailable();
        if (runtime == null) {
            log.warn("AdapterRuntime unavailable, fallback sync chat");
            return handleSync(msgInfo, openId, conversationId, content, imageIds);
        }

        AtomicInteger sent = new AtomicInteger();
        StreamReplyFlusher flusher = new StreamReplyFlusher(
                properties.getStreamMinChars(),
                part -> {
                    pushPart(runtime, msgInfo, part);
                    sent.incrementAndGet();
                });

        String full;
        try {
            full = client.chatStream(
                    properties.getDefaultAgentId(),
                    openId,
                    conversationId,
                    content,
                    imageIds,
                    properties.getStreamTimeoutMs(),
                    flusher::append
            );
            flusher.finish();
        } catch (Exception e) {
            String friendly = AgentUserReply.fromThrowable(e);
            log.warn("Agent stream failed openId={} conversationId={} sent={} reply={} err={}",
                    openId, conversationId, sent.get(), friendly, e.toString());
            if (sent.get() == 0) {
                return new AgentOutcome.Reply(ReplyInfo.text(friendly, msgInfo));
            }
            pushPart(runtime, msgInfo, friendly);
            return new AgentOutcome.Handled("stream-error-partial");
        }

        String sanitized = AgentUserReply.fromAnswer(full);
        if (sent.get() == 0) {
            // 模型没吐字，或分片条件未触发：兜底一条
            log.info("Agent stream empty fragments openId={} conversationId={} full={}",
                    openId, conversationId, preview(full));
            return new AgentOutcome.Reply(ReplyInfo.text(sanitized, msgInfo));
        }
        // 全文被识别成错误串且与已发内容不同时，补一句友好提示
        if (!sanitized.equals(full == null ? "" : full) && looksLikeTechnicalDump(full)) {
            pushPart(runtime, msgInfo, sanitized);
        }
        log.info("Agent stream done openId={} conversationId={} userId={} parts={} full={}",
                openId, conversationId, msgInfo.userId(), sent.get(), preview(full));
        return new AgentOutcome.Handled("streamed:" + sent.get());
    }

    private void pushPart(AdapterRuntime runtime, MsgInfo msgInfo, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        String trimmed = part.trim();
        if (AgentOutboundImages.looksLikeImageUrl(trimmed)) {
            pushImageOrFallback(runtime, msgInfo, trimmed);
            return;
        }
        pushText(runtime, msgInfo, part, true);
    }

    private void pushImageOrFallback(AdapterRuntime runtime, MsgInfo msgInfo, String imageUrl) {
        if (!OutboundImageProbe.isFetchableImage(imageUrl)) {
            log.warn("Skip broken image url (probe failed), fallback text userId={} url={}",
                    msgInfo.userId(), preview(imageUrl, 160));
            // 不把死链当图片丢给 Golem；也不把明显幻觉短链刷给用户
            return;
        }
        ReplyInfo merged = ReplyInfo.merge(ReplyInfo.image(imageUrl, msgInfo), msgInfo);
        try {
            runtime.push(msgInfo.platform(), merged).join();
            log.info("Agent stream push type=image userId={} preview={}",
                    msgInfo.userId(), preview(imageUrl, 160));
        } catch (Exception e) {
            log.warn("Agent stream sendImage failed, fallback text userId={} url={} err={}",
                    msgInfo.userId(), preview(imageUrl, 160), e.toString());
            pushText(runtime, msgInfo, imageUrl, false);
        }
    }

    private void pushText(AdapterRuntime runtime, MsgInfo msgInfo, String text, boolean fatalOnError) {
        ReplyInfo merged = ReplyInfo.merge(ReplyInfo.text(text, msgInfo), msgInfo);
        try {
            runtime.push(msgInfo.platform(), merged).join();
            log.info("Agent stream push type=text userId={} preview={}",
                    msgInfo.userId(), preview(text));
        } catch (Exception e) {
            log.warn("Agent stream push text failed userId={} err={}", msgInfo.userId(), e.toString());
            if (fatalOnError) {
                throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
            }
        }
    }

    private static boolean looksLikeTechnicalDump(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase();
        return text.startsWith("[ERROR]")
                || lower.contains("stream processing failed")
                || lower.contains("content is blocked");
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
