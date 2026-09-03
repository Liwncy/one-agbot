package me.liwncy.agbot.adapter.golem.mcp;

import me.liwncy.agbot.adapter.golem.GolemProperties;
import me.liwncy.agbot.adapter.golem.api.GolemApiClient;
import me.liwncy.agbot.adapter.golem.wechat.WechatChatRecordXml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;

import java.util.List;

/**
 * Golem 专属：随机搜一个微信用户，返回可原样发出的聊天记录卡片行。
 */
public class GolemRandomFriendMcpTool {
    private static final Logger log = LoggerFactory.getLogger(GolemRandomFriendMcpTool.class);
    private static final String GUIDE_AVATAR_URL =
            "https://wx.qlogo.cn/mmhead/ver_1/t4vmY8hTfx0rJnTygqKyIIX9PicUDwaEhib5Ex843gTJk7UVSKTcic4mlPt9rq2U7vMOJdXdHpdOSXoL0Ez8CicxWB3ojMh107wzggmTmKQn4bnxcL6lDVKx0mX91koST8x2/132";
    private static final String DEFAULT_GUIDE_NAME = "小聪明儿";

    private final RandomFriendFinder finder;
    private final GolemProperties properties;

    public GolemRandomFriendMcpTool(GolemApiClient apiClient, GolemProperties properties) {
        this.finder = new RandomFriendFinder(apiClient);
        this.properties = properties;
    }

    @McpTool(
            name = "golem_random_friend",
            description = "随机搜一个微信用户，返回一张可发出的聊天记录卡片。"
                    + "对方说蕉个朋友、捞个好友、交个朋友、来个朋友、随机朋友、随机名片时用。"
                    + "不要自己编手机号或名片。成功后配文一句，下一行把返回的 app: 行原样发出。"
                    + "闲聊接话不要调。"
    )
    public String randomFriend() {
        try {
            RandomFriendFinder.Hit hit = finder.search();
            if (hit.candidate() == null) {
                log.info("MCP golem_random_friend empty attempts={} lastPhone={}", hit.attempts(), hit.phone());
                return "status=empty\n"
                        + "attempts=" + hit.attempts() + "\n"
                        + "hint=翻了几轮都没人在线，用人话说这次没捞到，不要编一个人。";
            }
            RandomFriendFinder.Candidate candidate = hit.candidate();
            String xml = WechatChatRecordXml.build(
                    "💘 月老小纸条",
                    "替你牵来一段缘分：" + candidate.nickname(),
                    "✨ 资料已经替你整理好啦，可复制手机号 " + hit.phone() + " 自行搜索",
                    cardItems(candidate, hit.phone())
            );
            log.info("MCP golem_random_friend hit attempts={} username={} nick={} gender={}",
                    hit.attempts(), candidate.username(), candidate.nickname(), candidate.gender());
            return "status=ok\n"
                    + "caption=捞到一个，先看看合不合眼缘\n"
                    + "下面这一行从 app: 开头原样发出，不要改 xml，不要拆行，不要念给用户听：\n"
                    + WechatChatRecordXml.emitLine(xml);
        } catch (Exception e) {
            log.warn("MCP golem_random_friend failed: {}", e.toString());
            return "status=error\nhint=这次没找成，用人话说没弄成，不要念字段名或错误码。";
        }
    }

    private List<WechatChatRecordXml.Item> cardItems(RandomFriendFinder.Candidate candidate, String phone) {
        long now = System.currentTimeMillis();
        String guide = guideName();
        return List.of(
                new WechatChatRecordXml.Item(
                        guide,
                        "💌 悄悄递你一张缘分小纸条，先看看这位有没有合你的眼缘。\n💕 资料我已经替你整理成好读的小卡片啦。",
                        GUIDE_AVATAR_URL,
                        now),
                new WechatChatRecordXml.Item(
                        candidate.nickname(),
                        profileSummary(candidate, phone),
                        candidate.avatarUrl(),
                        now + 1000),
                new WechatChatRecordXml.Item(
                        guide,
                        "🌙 要是觉得有点心动，就复制手机号 " + phone
                                + "，去微信里搜一下，顺手问一句“处吗？宝贝😘”，万一真成了呢。\n🍀 说不定这次，真能顺手牵出一段小缘分。",
                        GUIDE_AVATAR_URL,
                        now + 2000)
        );
    }

    private String guideName() {
        String name = properties.getBotWechatName();
        return name == null || name.isBlank() ? DEFAULT_GUIDE_NAME : name.trim();
    }

    private static String profileSummary(RandomFriendFinder.Candidate candidate, String phone) {
        return String.join("\n",
                "📛 昵称：" + candidate.nickname(),
                "📱 手机号：" + phone,
                "🔎 微信号：" + orDefault(candidate.alias(), "未公开"),
                "🧍 性别：" + genderLabel(candidate.gender()),
                "🌍 地区：" + regionLabel(candidate),
                "🪪 关系：" + (candidate.username().endsWith("@stranger") ? "目前还没有加上好友" : "已经在联系人范围里了"),
                "✨ 账号感觉：" + (candidate.verifyFlag() > 0 ? "像是带一点特别身份的账号" : "看起来是普通个人账号"),
                "📝 个签：" + orDefault(candidate.sign(), "这个人还没留下个性签名"),
                "💭 小印象：" + impression(candidate)
        );
    }

    private static String genderLabel(int gender) {
        if (gender == 1) {
            return "男";
        }
        if (gender == 2) {
            return "女";
        }
        return "未知";
    }

    private static String regionLabel(RandomFriendFinder.Candidate candidate) {
        List<String> parts = new java.util.ArrayList<>();
        addIfPresent(parts, candidate.country());
        addIfPresent(parts, candidate.province());
        addIfPresent(parts, candidate.city());
        return parts.isEmpty() ? "未填写" : String.join(" / ", parts);
    }

    private static String impression(RandomFriendFinder.Candidate candidate) {
        if (candidate.verifyFlag() > 0) {
            return "看起来像是自带一点特别身份光环。";
        }
        if (!candidate.city().isBlank() || !candidate.province().isBlank()) {
            return "像是在人海里刚好被月老翻到的一张小卡片。";
        }
        return "资料不算张扬，留一点想象空间也挺好。";
    }

    private static void addIfPresent(List<String> parts, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(value.trim());
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
