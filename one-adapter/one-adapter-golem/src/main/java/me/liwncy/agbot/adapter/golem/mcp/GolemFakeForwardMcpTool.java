package me.liwncy.agbot.adapter.golem.mcp;

import me.liwncy.agbot.adapter.golem.wechat.WechatChatRecordXml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.util.ArrayList;
import java.util.List;

/**
 * Golem 专属：一次传入角色和台词，返回可发出的聊天记录卡片。
 */
public class GolemFakeForwardMcpTool {
    private static final Logger log = LoggerFactory.getLogger(GolemFakeForwardMcpTool.class);
    private static final String DEFAULT_TITLE = "群聊的聊天记录";

    @McpTool(
            name = "golem_fake_forward",
            description = "把一段对白做成微信聊天记录卡片。"
                    + "对方说编聊天记录、做聊天记录卡、假聊天记录、伪造聊天记录、编一段群聊记录时用。"
                    + "本条有「被@」时，用这些人当角色：script 用他们的名字，avatars 用被@行里的头像 URL，不要编。"
                    + "script 每行「姓名|时间|内容」，时间可空；有角色没台词先问，不要自己编。"
                    + "成功后配文一句，下一行把返回的 app: 行原样发出。闲聊接话不要调。"
    )
    public String fakeForward(
            @McpToolParam(description = "必填。每行一条：姓名|时间|内容。时间用 HH:mm 或 YYYY-MM-DD HH:mm，可空写成 姓名||内容 或 姓名|内容")
            String script,
            @McpToolParam(description = "可选。卡片标题，默认「群聊的聊天记录」")
            String title,
            @McpToolParam(description = "可选。头像，每行 姓名=https://... ；没有可不填")
            String avatars
    ) {
        try {
            List<FakeForwardScript.Line> lines = FakeForwardScript.parse(script, avatars);
            List<WechatChatRecordXml.Item> items = new ArrayList<>(lines.size());
            for (FakeForwardScript.Line line : lines) {
                items.add(new WechatChatRecordXml.Item(
                        line.name(), line.content(), line.avatarUrl(), line.timestampMs()));
            }
            String cardTitle = (title == null || title.isBlank()) ? DEFAULT_TITLE : title.trim();
            String xml = WechatChatRecordXml.build(cardTitle, null, null, items);
            log.info("MCP golem_fake_forward ok title={} items={}", cardTitle, items.size());
            return "status=ok\n"
                    + "caption=编好了\n"
                    + "下面这一行从 app: 开头原样发出，不要改 xml，不要拆行，不要念给用户听：\n"
                    + WechatChatRecordXml.emitLine(xml);
        } catch (IllegalArgumentException e) {
            log.info("MCP golem_fake_forward ask: {}", e.getMessage());
            return "status=ask\nhint=" + e.getMessage() + "\n对方没给齐就只追问缺的，不要自己编台词。";
        } catch (Exception e) {
            log.warn("MCP golem_fake_forward failed: {}", e.toString());
            return "status=error\nhint=这次没做成，用人话说没弄成，不要念字段名或错误码。";
        }
    }
}
