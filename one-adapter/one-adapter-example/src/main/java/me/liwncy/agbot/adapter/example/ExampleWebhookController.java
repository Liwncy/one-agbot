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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 示例通道：入站 / 主动 push / 撤回，用于验证通道契约。
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
        String msgId = blankToUuid(body.msgId());
        Map<String, Object> extra = body.extra() == null ? Map.of() : body.extra();
        MsgInfo msg = new MsgInfo(
                ExampleAdapter.PLATFORM,
                accountId,
                body.userId(),
                body.userName(),
                body.groupId(),
                body.groupName(),
                body.msg(),
                msgId,
                body.fromType() == null || body.fromType().isBlank() ? "Social" : body.fromType(),
                MsgType.normalize(body.msgType()),
                body.path(),
                body.replyToMsgId(),
                System.currentTimeMillis(),
                extra
        );
        try {
            runtime.receive(msg).get(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new ServiceException("handle message failed: " + e.getMessage());
        }
        ReplyInfo reply = adapter.lastReply(accountId, body.userId(),
                body.groupId() == null || body.groupId().isBlank() ? "0" : body.groupId());
        Map<String, Object> data = new HashMap<>();
        data.put("msgId", msgId);
        data.put("inboundType", msg.msgType());
        data.put("reply", reply == null ? "" : reply.msg());
        data.put("replyType", reply == null ? "" : reply.type());
        data.put("replyPath", reply == null ? "" : reply.path());
        return Result.success(data);
    }

    /**
     * 主动推送：验证 Runtime.push → adapter.push。
     */
    @PostMapping("/{accountId}/push")
    public Result<Map<String, Object>> push(@PathVariable String accountId,
                                            @RequestBody ExamplePushRequest body) {
        if (body == null) {
            throw new ServiceException("body is required");
        }
        ReplyInfo reply = new ReplyInfo(
                MsgType.normalize(body.type()),
                body.msg(),
                body.path(),
                body.userId(),
                body.groupId() == null || body.groupId().isBlank() ? "0" : body.groupId(),
                body.toMsgId(),
                accountId,
                body.remind(),
                body.title(),
                body.url(),
                body.extra() == null ? Map.of() : body.extra()
        );
        try {
            String outId = runtime.push(ExampleAdapter.PLATFORM, reply).get(30, TimeUnit.SECONDS);
            return Result.success(Map.of(
                    "msgId", outId == null ? "" : outId,
                    "type", reply.type(),
                    "capabilities", adapter.capabilities().toString()
            ));
        } catch (Exception e) {
            throw new ServiceException("push failed: " + e.getMessage());
        }
    }

    @PostMapping("/{accountId}/delMsg")
    public Result<Map<String, Object>> delMsg(@PathVariable String accountId,
                                              @RequestBody ExampleDelMsgRequest body) {
        List<String> ids = body == null || body.msgIds() == null ? List.of() : body.msgIds();
        try {
            runtime.delMsg(ExampleAdapter.PLATFORM, ids).get(30, TimeUnit.SECONDS);
            return Result.success(Map.of(
                    "deleted", adapter.deletedMsgIds(),
                    "accountId", accountId
            ));
        } catch (Exception e) {
            throw new ServiceException("delMsg failed: " + e.getMessage());
        }
    }

    public record ExampleInboundRequest(
            String userId,
            String userName,
            String groupId,
            String groupName,
            String msg,
            String msgId,
            String msgType,
            String path,
            String replyToMsgId,
            String fromType,
            Map<String, Object> extra
    ) {
    }

    public record ExamplePushRequest(
            String type,
            String msg,
            String path,
            String userId,
            String groupId,
            String toMsgId,
            String remind,
            String title,
            String url,
            Map<String, Object> extra
    ) {
    }

    public record ExampleDelMsgRequest(List<String> msgIds) {
    }

    private static String blankToUuid(String msgId) {
        return msgId == null || msgId.isBlank()
                ? UUID.randomUUID().toString().replace("-", "")
                : msgId;
    }
}
