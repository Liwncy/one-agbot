package me.liwncy.agbot.adapter.example;

import me.liwncy.agbot.common.core.Result;
import me.liwncy.agbot.common.core.exception.ServiceException;
import me.liwncy.agbot.kernel.api.message.MsgInfo;
import me.liwncy.agbot.kernel.api.message.MsgType;
import me.liwncy.agbot.kernel.api.message.ReplyInfo;
import me.liwncy.agbot.kernel.api.runtime.AdapterRuntime;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 示例通道入站：模拟平台推送消息。
 */
@RestController
@RequestMapping("/adapter/example")
public class ExampleWebhookController {

    private final AdapterRuntime runtime;
    private final ExampleAdapter adapter;

    public ExampleWebhookController(AdapterRuntime runtime, ExampleAdapter adapter) {
        this.runtime = runtime;
        this.adapter = adapter;
    }

    @PostMapping("/{accountId}/message")
    public Result<Map<String, Object>> onMessage(@PathVariable String accountId,
                                                 @RequestBody ExampleInboundRequest body) {
        if (body == null || body.userId() == null || body.userId().isBlank()) {
            throw new ServiceException("userId is required");
        }
        String msgId = body.msgId() == null || body.msgId().isBlank()
                ? UUID.randomUUID().toString().replace("-", "")
                : body.msgId();
        MsgInfo msg = new MsgInfo(
                ExampleAdapter.PLATFORM,
                accountId,
                body.userId(),
                body.userName(),
                body.groupId(),
                body.groupName(),
                body.msg(),
                msgId,
                "Social",
                MsgType.TEXT,
                null,
                body.replyToMsgId(),
                System.currentTimeMillis(),
                Map.of()
        );
        try {
            runtime.receive(msg).get(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new ServiceException("handle message failed: " + e.getMessage());
        }
        ReplyInfo reply = adapter.lastReply(accountId, body.userId(),
                body.groupId() == null || body.groupId().isBlank() ? "0" : body.groupId());
        return Result.success(Map.of(
                "msgId", msgId,
                "reply", reply == null ? "" : reply.msg(),
                "replyType", reply == null ? "" : reply.type()
        ));
    }

    public record ExampleInboundRequest(
            String userId,
            String userName,
            String groupId,
            String groupName,
            String msg,
            String msgId,
            String replyToMsgId
    ) {
    }
}
