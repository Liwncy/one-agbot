package me.liwncy.agbot.agent;

import com.aizuda.snail.ai.common.execption.SnailAiException;
import com.aizuda.snail.ai.common.model.Result;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiChatRequest;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiChatSyncResponse;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiUserAgentRequest;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiUserRegisterRequest;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiUserVO;
import com.aizuda.snail.ai.openapi.client.core.api.OpenApiAgentClient;
import com.aizuda.snail.ai.openapi.client.core.api.OpenApiChatClient;
import com.aizuda.snail.ai.openapi.client.core.api.OpenApiUserClient;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SnailAI OpenAPI 封装（对齐 RuoYi：注入官方 Client，不做手写 HTTP）。
 */
public class SnailAiOpenApiClient {
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

    public String chatSync(long agentId, String openId, String conversationId, String content) {
        ensureSubscribed(openId, agentId);
        OpenApiChatRequest request = new OpenApiChatRequest();
        request.setAgentId(agentId);
        request.setOpenId(openId);
        request.setConversationId(conversationId);
        request.setContent(content == null ? "" : content);
        Result<OpenApiChatSyncResponse> result = chatClient.chatSync(request);
        OpenApiChatSyncResponse data = requireData(result, "同步对话失败");
        String answer = data.getContent();
        return answer == null ? "" : answer;
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
