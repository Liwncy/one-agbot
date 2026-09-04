package me.liwncy.agbot.adapter.golem.mcp;

import me.liwncy.agbot.adapter.golem.api.GolemChatroomRoster;
import me.liwncy.agbot.adapter.golem.wechat.WechatChatRecordXml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Golem 专属：一次传入角色和台词，返回可发出的聊天记录卡片。
 * <p>头像不由调用方给，服务端拿群花名册按角色名去配，配不上就不配。</p>
 */
public class GolemFakeForwardMcpTool {
    private static final Logger log = LoggerFactory.getLogger(GolemFakeForwardMcpTool.class);
    private static final String DEFAULT_TITLE = "群聊的聊天记录";

    private final GolemChatroomRoster roster;

    public GolemFakeForwardMcpTool(GolemChatroomRoster roster) {
        this.roster = roster;
    }

    @McpTool(
            name = "golem_fake_forward",
            description = "把一段对白做成微信聊天记录卡片。"
                    + "对方说编聊天记录、做聊天记录卡、假聊天记录、伪造聊天记录、编一段群聊记录时用。"
                    + "script 每行「姓名|时间|内容」，时间可空；有角色没台词先问，不要自己编。"
                    + "角色写群里显示的名字（被@的人就照抄「被@」行里的名字），是群友的话卡片会自动署他的微信名并配上头像，你不用管。"
                    + "成功后配文一句，下一行把返回的 app: 行原样发出。闲聊接话不要调。"
    )
    public String fakeForward(
            @McpToolParam(description = "必填。每行一条：姓名|时间|内容。时间用 HH:mm 或 YYYY-MM-DD HH:mm，可空写成 姓名||内容 或 姓名|内容")
            String script,
            @McpToolParam(description = "可选。卡片标题，默认「群聊的聊天记录」")
            String title,
            @McpToolParam(description = "可选。当前群 id，照抄上下文里 scope=group: 后面那串（如 123@chatroom）。填了才能给群友配上真头像；私聊留空")
            String group
    ) {
        try {
            List<FakeForwardScript.Line> lines = FakeForwardScript.parse(script);
            Map<String, GolemChatroomRoster.Member> roles = resolveRoles(group, lines);
            List<WechatChatRecordXml.Item> items = new ArrayList<>(lines.size());
            for (FakeForwardScript.Line line : lines) {
                GolemChatroomRoster.Member member = roles.get(line.name());
                String name = member == null ? line.name() : displayOf(member);
                String avatar = member == null ? "" : member.avatar();
                items.add(new WechatChatRecordXml.Item(name, line.content(), avatar, line.timestampMs()));
            }
            String cardTitle = (title == null || title.isBlank()) ? DEFAULT_TITLE : title.trim();
            String xml = WechatChatRecordXml.build(cardTitle, null, null, items);
            log.info("MCP golem_fake_forward ok title={} items={} group={} matched={}",
                    cardTitle, items.size(), group, roles.size());
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

    /**
     * 角色名按群昵称或微信名找人，找到才换名配头像。
     * 虚构角色、重名、查不到一律保留调用方写的名字且不给头像，不拿别人的顶。
     */
    private Map<String, GolemChatroomRoster.Member> resolveRoles(String group, List<FakeForwardScript.Line> lines) {
        Map<String, GolemChatroomRoster.Member> roles = new HashMap<>();
        if (!GolemChatroomRoster.isChatroom(group)) {
            return roles;
        }
        for (FakeForwardScript.Line line : lines) {
            if (roles.containsKey(line.name())) {
                continue;
            }
            GolemChatroomRoster.Member member = roster.findByName(group, line.name());
            if (member != null) {
                roles.put(line.name(), member);
            }
        }
        return roles;
    }

    /** 卡片上按微信名署名，群昵称只用来找人。 */
    private static String displayOf(GolemChatroomRoster.Member member) {
        return member.nickname().isBlank() ? member.name() : member.nickname();
    }
}
