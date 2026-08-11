package me.liwncy.agbot.agent;

import com.aizuda.snail.ai.common.dto.agent.ChatStreamResponse;
import com.aizuda.snail.ai.common.execption.SnailAiException;
import com.aizuda.snail.ai.common.model.Result;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiChatAttachmentRequest;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiChatRequest;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiChatStreamEvent;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiChatSyncResponse;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiResourceResponse;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiResourceUploadRequest;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiUserAgentRequest;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiUserRegisterRequest;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiUserVO;
import com.aizuda.snail.ai.common.util.JsonUtil;
import com.aizuda.snail.ai.openapi.client.core.api.OpenApiAgentClient;
import com.aizuda.snail.ai.openapi.client.core.api.OpenApiChatClient;
import com.aizuda.snail.ai.openapi.client.core.api.OpenApiUserClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * SnailAI OpenAPI 封装（对齐 RuoYi：注入官方 Client，不做手写 HTTP）。
 */
public class SnailAiOpenApiClient {
    private static final Logger log = LoggerFactory.getLogger(SnailAiOpenApiClient.class);
    private static final int SNAIL_AI_SUCCESS = 1;

    private final OpenApiUserClient userClient;
    private final OpenApiChatClient chatClient;
    private final OpenApiAgentClient agentClient;
    private final Map<String, String> openIdCache = new ConcurrentHashMap<>();
    private final Set<String> subscribedCache = ConcurrentHashMap.newKeySet();

    public SnailAiOpenApiClient(OpenApiUserClient userClient,
                                OpenApiChatClient chatClient,
                                OpenApiAgentClient agentClient) {
        this.userClient = userClient;
        this.chatClient = chatClient;
        this.agentClient = agentClient;
    }

    public String ensureOpenId(String externalId, String nickname) {
        return openIdCache.computeIfAbsent(externalId, id -> register(id, nickname));
    }

    private String register(String externalId, String nickname) {
        OpenApiUserRegisterRequest request = new OpenApiUserRegisterRequest();
        request.setExternalId(externalId);
        request.setNickname(nickname == null || nickname.isBlank() ? externalId : nickname);
        Result<OpenApiUserVO> result = userClient.register(request);
        OpenApiUserVO user = requireData(result, "注册 OpenAPI 用户失败");
        if (user.getOpenId() == null || user.getOpenId().isBlank()) {
            throw new SnailAiException("注册 OpenAPI 用户失败，openId 为空");
        }
        return user.getOpenId();
    }

    /**
     * 对话前确保已订阅目标智能体（服务端幂等）。
     */
    public void ensureSubscribed(String openId, long agentId) {
        String key = openId + ":" + agentId;
        if (subscribedCache.contains(key)) {
            return;
        }
        OpenApiUserAgentRequest request = new OpenApiUserAgentRequest();
        request.setOpenId(openId);
        request.setAgentId(agentId);
        Result<Void> result = agentClient.subscribeAgent(request);
        requireOk(result, "订阅智能体失败");
        subscribedCache.add(key);
    }

    public long uploadImage(String openId, String originalName, byte[] content) {
        OpenApiResourceUploadRequest request = new OpenApiResourceUploadRequest();
        request.setOpenId(openId);
        request.setOriginalName(originalName == null || originalName.isBlank() ? "image.jpg" : originalName);
        request.setFileSize((long) content.length);
        request.setContent(content);
        request.setBizType("ATTACHMENT");
        Result<OpenApiResourceResponse> result = chatClient.uploadResource(request);
        OpenApiResourceResponse data = requireData(result, "上传对话资源失败");
        if (data.getId() == null) {
            throw new SnailAiException("上传对话资源失败，resourceId 为空");
        }
        log.info("Uploaded chat image resourceId={} size={} name={}", data.getId(), content.length, originalName);
        return data.getId();
    }

    public String chatSync(long agentId, String openId, String conversationId, String content) {
        return chatSync(agentId, openId, conversationId, content, List.of());
    }

    public String chatSync(long agentId, String openId, String conversationId, String content,
                           List<Long> imageResourceIds) {
        OpenApiChatRequest request = buildChatRequest(agentId, openId, conversationId, content, imageResourceIds);
        Result<OpenApiChatSyncResponse> result = chatClient.chatSync(request);
        OpenApiChatSyncResponse data = requireData(result, "同步对话失败");
        String answer = data.getContent();
        return answer == null ? "" : answer;
    }

    /**
     * 流式对话：对每个文本增量调用 {@code onTextDelta}，阻塞至结束；返回拼好的全文。
     */
    public String chatStream(long agentId, String openId, String conversationId, String content,
                             List<Long> imageResourceIds, long timeoutMs, Consumer<String> onTextDelta) {
        OpenApiChatRequest request = buildChatRequest(agentId, openId, conversationId, content, imageResourceIds);
        StringBuilder full = new StringBuilder();
        AtomicReference<String> error = new AtomicReference<>();
        Flux<OpenApiChatStreamEvent> flux = chatClient.chatStream(request);
        Duration timeout = Duration.ofMillis(Math.max(1_000L, timeoutMs));
        flux.doOnNext(event -> {
                    if (event == null || event.getType() == null) {
                        return;
                    }
                    switch (event.getType()) {
                        case OpenApiChatStreamEvent.TYPE_TEXT -> {
                            String delta = extractTextDelta(event.getData());
                            if (delta != null && !delta.isEmpty()) {
                                full.append(delta);
                                if (onTextDelta != null) {
                                    onTextDelta.accept(delta);
                                }
                            }
                        }
                        case OpenApiChatStreamEvent.TYPE_ERROR ->
                                error.set(extractErrorMessage(event.getData()));
                        case OpenApiChatStreamEvent.TYPE_THINKING, OpenApiChatStreamEvent.TYPE_DONE -> {
                            // thinking 不转发；done 的 fullText 仅作兜底，正文已由 text 增量累积
                        }
                        default -> log.debug("Ignore stream event type={}", event.getType());
                    }
                })
                .blockLast(timeout);
        if (error.get() != null && !error.get().isBlank()) {
            throw new SnailAiException(error.get());
        }
        return full.toString();
    }

    private OpenApiChatRequest buildChatRequest(long agentId, String openId, String conversationId,
                                                String content, List<Long> imageResourceIds) {
        ensureSubscribed(openId, agentId);
        OpenApiChatRequest request = new OpenApiChatRequest();
        request.setAgentId(agentId);
        request.setOpenId(openId);
        request.setConversationId(conversationId);
        request.setContent(content == null || content.isBlank() ? "请看这张图片" : content);
        if (imageResourceIds != null && !imageResourceIds.isEmpty()) {
            List<OpenApiChatAttachmentRequest> attachments = new ArrayList<>();
            for (Long resourceId : imageResourceIds) {
                if (resourceId == null) {
                    continue;
                }
                OpenApiChatAttachmentRequest attachment = new OpenApiChatAttachmentRequest();
                attachment.setResourceId(resourceId);
                attachment.setType("IMAGE");
                attachments.add(attachment);
            }
            if (!attachments.isEmpty()) {
                request.setAttachments(attachments);
            }
        }
        return request;
    }

    private static String extractTextDelta(String data) {
        if (data == null || data.isBlank()) {
            return "";
        }
        String raw = data.trim();
        try {
            ChatStreamResponse response = JsonUtil.parseObject(raw, ChatStreamResponse.class);
            if (response != null && response.getContent() != null) {
                return response.getContent();
            }
        } catch (Exception ignored) {
            // fall through: some gateways may emit plain text
        }
        if (raw.startsWith("{")) {
            return "";
        }
        return raw;
    }

    private static String extractErrorMessage(String data) {
        if (data == null || data.isBlank()) {
            return "流式对话失败";
        }
        String raw = data.trim();
        try {
            ChatStreamResponse response = JsonUtil.parseObject(raw, ChatStreamResponse.class);
            if (response != null && response.getErrorMessage() != null && !response.getErrorMessage().isBlank()) {
                return response.getErrorMessage();
            }
        } catch (Exception ignored) {
            // OpenAPI error payload is {"message":"..."}
        }
        int key = raw.indexOf("\"message\"");
        if (key >= 0) {
            int colon = raw.indexOf(':', key);
            int q1 = colon >= 0 ? raw.indexOf('"', colon + 1) : -1;
            int q2 = q1 >= 0 ? raw.indexOf('"', q1 + 1) : -1;
            if (q1 >= 0 && q2 > q1) {
                return raw.substring(q1 + 1, q2);
            }
        }
        return raw;
    }

    private static <T> T requireData(Result<T> result, String action) {
        requireOk(result, action);
        if (result.getData() == null) {
            throw new SnailAiException(action + "，返回为空");
        }
        return result.getData();
    }

    private static void requireOk(Result<?> result, String action) {
        if (result == null) {
            throw new SnailAiException(action + "，返回为空");
        }
        if (result.getStatus() != SNAIL_AI_SUCCESS) {
            throw new SnailAiException(result.getMessage() == null ? action : result.getMessage());
        }
    }
}
