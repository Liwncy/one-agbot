package me.liwncy.agbot.agent.roleplay;

import me.liwncy.agbot.kernel.api.message.ChannelExtraKeys;
import me.liwncy.agbot.kernel.api.message.MsgInfo;
import me.liwncy.agbot.kernel.api.message.MsgType;
import me.liwncy.agbot.kernel.api.session.ConversationMapper;
import me.liwncy.agbot.kernel.api.session.SessionKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 角色：口令拦截、Redis 绑定、进模型前注入。
 */
public class RoleplayService {
    private static final Logger log = LoggerFactory.getLogger("agbot.agent");
    private static final Pattern ADD_CMD = Pattern.compile(
            "^(?:加角色|增加角色|新增角色)\\s*[:：]?\\s*(.*)$",
            Pattern.DOTALL);
    private static final Pattern LEADING_AT = Pattern.compile("^[@＠][^\\s\\u2005\\u2006]+\\s*");
    private static final Pattern WECHAT_NOISE = Pattern.compile("[\\u2005\\u2006\\u2009\\u200A\\u200B\\uFEFF\\u00A0]+");

    private final RoleplaySessionStore store;
    private final ConversationMapper conversations;
    private final RoleplayCatalog catalog;

    public RoleplayService(RoleplaySessionStore store,
                           ConversationMapper conversations,
                           RoleplayCatalog catalog) {
        this.store = store;
        this.conversations = conversations;
        this.catalog = catalog;
    }

    /**
     * 整句换角色 / 查当前角色。已处理则返回回复，不进模型。
     */
    public String tryHandleCommand(MsgInfo msg) {
        if (msg == null || !MsgType.TEXT.equals(MsgType.normalize(msg.msgType()))) {
            return null;
        }
        String stripped = stripPrefix(msg.msg());
        if (stripped.isEmpty()) {
            return null;
        }
        String addReply = tryAddCharacter(msg, stripped);
        if (addReply != null) {
            return addReply;
        }
        String command = normalizeCommand(stripped);
        if (command.isEmpty()) {
            return null;
        }
        String scopeKey = SessionKeys.of(msg);
        if (RoleplayCatalog.isQuery(command)) {
            RoleplayCharacter current = current(msg);
            String reply = current == null ? "现在没演" : "现在演" + current.name();
            log.info("Roleplay query scopeKey={} role={}", scopeKey,
                    current == null ? RoleplayCatalog.NORMAL_ID : current.id());
            return reply;
        }
        String targetId = catalog.matchCommandId(command);
        if (targetId == null) {
            return null;
        }
        String previous = store.get(scopeKey);
        boolean changed;
        String reply;
        if (RoleplayCatalog.NORMAL_ID.equals(targetId)) {
            store.clear(scopeKey);
            changed = previous != null && !previous.isBlank();
            reply = "好，不当了。";
        } else {
            RoleplayCharacter character = catalog.get(targetId);
            if (character == null) {
                return null;
            }
            store.set(scopeKey, character.id());
            changed = !character.id().equals(previous);
            reply = character.ack();
        }
        if (changed) {
            conversations.reset(scopeKey);
        }
        log.info("Roleplay set scopeKey={} from={} to={} resetConv={}",
                scopeKey, previous == null ? RoleplayCatalog.NORMAL_ID : previous, targetId, changed);
        return reply;
    }

    public RoleplayCharacter current(MsgInfo msg) {
        if (msg == null) {
            return null;
        }
        String id = store.get(SessionKeys.of(msg));
        RoleplayCharacter character = catalog.get(id);
        if (id != null && !id.isBlank() && character == null) {
            store.clear(SessionKeys.of(msg));
        }
        return character;
    }

    /**
     * 演法垫在对方正文前。不用「怎么说话」当标题，以免盖掉系统提示词里的底线。
     */
    public String wrapUserContent(String content, RoleplayCharacter character) {
        if (character == null || character.instruction() == null || character.instruction().isBlank()) {
            return content == null ? "" : content;
        }
        String body = content == null ? "" : content;
        return character.instruction().trim() + "\n\n" + body;
    }

    private String tryAddCharacter(MsgInfo msg, String stripped) {
        Matcher matcher = ADD_CMD.matcher(stripped);
        if (!matcher.matches()) {
            return null;
        }
        if (!isOwner(msg)) {
            return "这个我加不了";
        }
        String rest = matcher.group(1) == null ? "" : matcher.group(1).trim();
        if (rest.isEmpty()) {
            return "名字和演法写一起，换行也行";
        }
        String[] lines = rest.split("\\R", 2);
        String first = lines[0].trim();
        int split = first.indexOf(' ');
        if (split < 0) {
            split = first.indexOf('　');
        }
        String name;
        String instruction;
        if (split < 0) {
            name = first;
            instruction = lines.length > 1 ? lines[1].trim() : "";
        } else {
            name = first.substring(0, split).trim();
            String sameLine = first.substring(split + 1).trim();
            instruction = lines.length > 1
                    ? (sameLine.isEmpty() ? lines[1].trim() : sameLine + "\n" + lines[1].trim())
                    : sameLine;
        }
        String error = catalog.create(name, instruction);
        if (error != null) {
            return error;
        }
        return "好，记下了。说扮演" + name.trim() + "就行";
    }

    private static boolean isOwner(MsgInfo msg) {
        Map<String, Object> extra = msg.extra();
        if (extra == null) {
            return false;
        }
        Object value = extra.get(ChannelExtraKeys.OWNER);
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "owner".equalsIgnoreCase(text);
    }

    static String stripPrefix(String raw) {
        if (raw == null) {
            return "";
        }
        String text = WECHAT_NOISE.matcher(raw.trim()).replaceAll(" ").trim();
        return LEADING_AT.matcher(text).replaceFirst("").trim();
    }

    static String normalizeCommand(String raw) {
        return stripPrefix(raw).replaceAll("\\s+", " ").trim();
    }
}
