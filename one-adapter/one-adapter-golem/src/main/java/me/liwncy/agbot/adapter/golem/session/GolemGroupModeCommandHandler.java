package me.liwncy.agbot.adapter.golem.session;

import me.liwncy.agbot.adapter.golem.GolemProperties;
import me.liwncy.agbot.adapter.golem.api.GolemApiClient;
import me.liwncy.agbot.adapter.golem.inbound.GolemMentionDetector;
import me.liwncy.agbot.kernel.api.message.MsgInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 群内主人切换响应配置（不走 Agent）。
 */
@Component
@ConditionalOnProperty(prefix = "agbot.adapter.golem", name = "enabled", havingValue = "true")
public class GolemGroupModeCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(GolemGroupModeCommandHandler.class);

    private static final Pattern MODE_CMD = Pattern.compile("^模式\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RULE_USERS = Pattern.compile("^规则\\s*用户\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RULE_KEYWORDS = Pattern.compile("^规则\\s*关键词\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RULE_CLEAR = Pattern.compile("^规则\\s*清空$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOLLOW_CMD = Pattern.compile("^跟聊\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHANCE_CMD = Pattern.compile("^(?:概率|随机)\\s*(\\d{1,3})\\s*%?$", Pattern.CASE_INSENSITIVE);

    private final GolemProperties properties;
    private final GolemGroupRespondPolicy respondPolicy;
    private final GolemApiClient apiClient;

    public GolemGroupModeCommandHandler(GolemProperties properties,
                                        GolemGroupRespondPolicy respondPolicy,
                                        GolemApiClient apiClient) {
        this.properties = properties;
        this.respondPolicy = respondPolicy;
        this.apiClient = apiClient;
    }

    public boolean tryHandle(MsgInfo msg) {
        if (msg == null || msg.isPrivateChat()) {
            return false;
        }
        String command = normalizeCommand(msg.msg());
        if (command.isEmpty()) {
            return false;
        }

        Parsed parsed = parse(command);
        if (parsed == null) {
            return false;
        }

        String ownerId = trim(properties.getOwnerWechatId());
        String receiver = msg.groupId();
        if (ownerId.isEmpty()) {
            reply(receiver, "群里还没设主人，没法改模式");
            return true;
        }
        if (!ownerId.equals(trim(msg.userId()))) {
            reply(receiver, "这事只有主人能定");
            return true;
        }

        return switch (parsed.kind()) {
            case SET_MODE -> {
                GolemGroupSettings cur = respondPolicy.get(msg.accountId(), msg.groupId());
                GolemGroupSettings next = cur.withMode(parsed.mode());
                if (parsed.mode() == GolemGroupRespondMode.RANDOM && next.replyChancePercent() <= 0) {
                    next = next.withReplyChancePercent(GolemGroupSettings.DEFAULT_REPLY_CHANCE);
                }
                respondPolicy.save(msg.accountId(), msg.groupId(), next);
                String extra = parsed.mode() == GolemGroupRespondMode.RANDOM
                        ? ("；" + next.chanceLabel() + "（可发「概率 15」改）")
                        : "";
                reply(receiver, "好了，这个群改成「" + parsed.mode().label() + "」——" + parsed.mode().tip() + extra);
                log.info("Group mode set accountId={} groupId={} mode={} chance={}",
                        msg.accountId(), msg.groupId(), parsed.mode(), next.replyChancePercent());
                yield true;
            }
            case SET_CHANCE -> {
                GolemGroupSettings cur = respondPolicy.get(msg.accountId(), msg.groupId());
                GolemGroupSettings next = cur.withReplyChancePercent(parsed.chancePercent())
                        .withMode(GolemGroupRespondMode.RANDOM);
                respondPolicy.save(msg.accountId(), msg.groupId(), next);
                reply(receiver, "好了，随机模式，大约 " + next.replyChancePercent() + "% 会接一句（跟别人说话时仍不接）");
                log.info("Group chance set accountId={} groupId={} chance={}",
                        msg.accountId(), msg.groupId(), next.replyChancePercent());
                yield true;
            }
            case SET_FOLLOW_UP -> {
                respondPolicy.setFollowUpSeconds(msg.accountId(), msg.groupId(), parsed.followUpSeconds());
                String tip = parsed.followUpSeconds() <= 0
                        ? "跟聊关了，得 @ 我才续得上"
                        : ("跟聊开了，点名后 " + parsed.followUpSeconds() + " 秒内可免 @");
                reply(receiver, "好了，" + tip);
                yield true;
            }
            case SET_USERS -> {
                GolemGroupRule next = respondPolicy.getRule(msg.accountId(), msg.groupId())
                        .withUsers(parsed.tokens());
                respondPolicy.setRule(msg.accountId(), msg.groupId(), next);
                ensureRuleMode(msg);
                reply(receiver, "好了，白名单用户记上了：" + join(parsed.tokens()));
                yield true;
            }
            case SET_KEYWORDS -> {
                GolemGroupRule next = respondPolicy.getRule(msg.accountId(), msg.groupId())
                        .withKeywords(parsed.tokens());
                respondPolicy.setRule(msg.accountId(), msg.groupId(), next);
                ensureRuleMode(msg);
                reply(receiver, "好了，关键词记上了：" + join(parsed.tokens()));
                yield true;
            }
            case CLEAR_RULE -> {
                respondPolicy.setRule(msg.accountId(), msg.groupId(), GolemGroupRule.EMPTY);
                reply(receiver, "好了，规则清干净了");
                yield true;
            }
            case HELP -> {
                reply(receiver, helpText(msg));
                yield true;
            }
        };
    }

    public String statusLine(MsgInfo msg) {
        if (msg == null || msg.isPrivateChat()) {
            return "";
        }
        return respondPolicy.get(msg.accountId(), msg.groupId()).summary();
    }

    private void ensureRuleMode(MsgInfo msg) {
        if (respondPolicy.getMode(msg.accountId(), msg.groupId()) != GolemGroupRespondMode.RULE) {
            respondPolicy.setMode(msg.accountId(), msg.groupId(), GolemGroupRespondMode.RULE);
        }
    }

    private String helpText(MsgInfo msg) {
        return "群响应可以这样切：\n"
                + "模式 点名 / 全量 / 随机 / 智能 / 规则\n"
                + "概率 15（随机模式百分比）\n"
                + "跟聊 关 / 跟聊 60\n"
                + "规则 用户 wxid1 wxid2\n"
                + "规则 关键词 帮助 查\n"
                + "规则 清空\n"
                + "说明：@别人时我不会接；智能细则还没定，暂等同点名\n"
                + "现在：" + statusLine(msg);
    }

    private String normalizeCommand(String raw) {
        String text = GolemMentionDetector.stripMentionPrefix(
                raw, properties.getBotWechatId(), properties.getBotWechatName());
        return text == null ? "" : text.trim();
    }

    private static Parsed parse(String command) {
        String c = command.trim();
        String lower = c.toLowerCase(Locale.ROOT);

        if ("模式".equals(c) || "模式帮助".equals(c) || "模式说明".equals(c)
                || "跟聊".equals(c) || "跟聊帮助".equals(c) || "概率".equals(c)
                || "help mode".equals(lower) || "mode".equals(lower)) {
            return Parsed.help();
        }

        Matcher chance = CHANCE_CMD.matcher(c);
        if (chance.matches()) {
            int percent = Integer.parseInt(chance.group(1));
            return Parsed.chance(Math.min(100, Math.max(0, percent)));
        }

        Matcher mode = MODE_CMD.matcher(c);
        if (mode.matches()) {
            String arg = mode.group(1).trim();
            if (arg.isEmpty() || "帮助".equals(arg) || "说明".equals(arg) || "?".equals(arg)) {
                return Parsed.help();
            }
            // 「模式 随机 20」
            Matcher modeChance = Pattern.compile("^随机\\s*(\\d{1,3})\\s*%?$", Pattern.CASE_INSENSITIVE).matcher(arg);
            if (modeChance.matches()) {
                return Parsed.chance(Math.min(100, Math.max(0, Integer.parseInt(modeChance.group(1)))));
            }
            GolemGroupRespondMode m = GolemGroupRespondMode.parse(arg);
            return m == null ? Parsed.help() : Parsed.mode(m);
        }

        GolemGroupRespondMode shortMode = switch (lower) {
            case "全量模式", "全都回" -> GolemGroupRespondMode.FULL;
            case "点名模式", "要@" -> GolemGroupRespondMode.MENTION;
            case "规则模式" -> GolemGroupRespondMode.RULE;
            case "随机模式" -> GolemGroupRespondMode.RANDOM;
            case "智能模式" -> GolemGroupRespondMode.SMART;
            default -> null;
        };
        if (shortMode != null) {
            return Parsed.mode(shortMode);
        }

        Matcher follow = FOLLOW_CMD.matcher(c);
        if (follow.matches()) {
            Integer seconds = parseFollowUp(follow.group(1).trim());
            return seconds == null ? Parsed.help() : Parsed.followUp(seconds);
        }
        if ("跟聊关".equals(c) || "关闭跟聊".equals(c) || "关掉跟聊".equals(c)) {
            return Parsed.followUp(0);
        }

        Matcher users = RULE_USERS.matcher(c);
        if (users.matches()) {
            return Parsed.users(splitTokens(users.group(1)));
        }
        Matcher kws = RULE_KEYWORDS.matcher(c);
        if (kws.matches()) {
            return Parsed.keywords(splitTokens(kws.group(1)));
        }
        if (RULE_CLEAR.matcher(c).matches()) {
            return new Parsed(Kind.CLEAR_RULE, null, 0, 0, List.of());
        }
        return null;
    }

    private static Integer parseFollowUp(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT)
                .replace("秒钟", "")
                .replace("秒", "")
                .replace("s", "")
                .trim();
        if (t.isEmpty()) {
            return null;
        }
        if ("关".equals(t) || "关闭".equals(t) || "off".equals(t) || "0".equals(t) || "停".equals(t)) {
            return 0;
        }
        if ("开".equals(t) || "on".equals(t)) {
            return 60;
        }
        try {
            return Math.max(0, Integer.parseInt(t));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> splitTokens(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String normalized = raw.replace(',', ' ').replace('，', ' ').replace(';', ' ').replace('；', ' ');
        List<String> out = new ArrayList<>();
        for (String part : normalized.split("\\s+")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return List.copyOf(out);
    }

    private static String join(List<String> tokens) {
        return tokens == null || tokens.isEmpty() ? "（空）" : String.join("、", tokens);
    }

    private void reply(String receiver, String content) {
        try {
            apiClient.sendText(receiver, content);
        } catch (Exception e) {
            log.error("Group mode command reply failed receiver={}", receiver, e);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private enum Kind {
        SET_MODE, SET_CHANCE, SET_FOLLOW_UP, SET_USERS, SET_KEYWORDS, CLEAR_RULE, HELP
    }

    private record Parsed(Kind kind, GolemGroupRespondMode mode, int followUpSeconds, int chancePercent,
                          List<String> tokens) {
        static Parsed help() {
            return new Parsed(Kind.HELP, null, 0, 0, List.of());
        }

        static Parsed mode(GolemGroupRespondMode mode) {
            return new Parsed(Kind.SET_MODE, mode, 0, 0, List.of());
        }

        static Parsed chance(int percent) {
            return new Parsed(Kind.SET_CHANCE, null, 0, percent, List.of());
        }

        static Parsed followUp(int seconds) {
            return new Parsed(Kind.SET_FOLLOW_UP, null, seconds, 0, List.of());
        }

        static Parsed users(List<String> tokens) {
            return new Parsed(Kind.SET_USERS, null, 0, 0, tokens);
        }

        static Parsed keywords(List<String> tokens) {
            return new Parsed(Kind.SET_KEYWORDS, null, 0, 0, tokens);
        }
    }
}
