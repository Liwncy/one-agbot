package me.liwncy.agbot.adapter.golem.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 群花名册：整份缓存 {@code GET /api/chatroom/members/{chatroom}}，按 wxid 或群里显示的名字查人。
 * <p>接口不支持按成员筛选，只能整份取回来自己建索引。
 * 名字重复（如「老牛子」「小牛子」不同人却同名）时索引里剔除该名，宁可没头像也不认错人。</p>
 */
public final class GolemChatroomRoster {
    private static final Logger log = LoggerFactory.getLogger(GolemChatroomRoster.class);
    private static final long TTL_MS = 10 * 60 * 1000L;
    /** 未命中时最快多久允许强刷一次，防止虚构角色名把接口打爆 */
    private static final long MIN_REFRESH_MS = 60 * 1000L;
    /** 零宽、Tag 字符、变体选择符等不可见字符，微信昵称里常见 */
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cf}\\s\\uFE00-\\uFE0F]+");

    private final GolemApiClient apiClient;
    private final ConcurrentHashMap<String, Snapshot> cache = new ConcurrentHashMap<>();

    public GolemChatroomRoster(GolemApiClient apiClient) {
        this.apiClient = apiClient;
    }

    /**
     * 群成员，{@code name} 为群里显示的名字（群昵称优先，没有才用联系人昵称）。
     */
    public record Member(String id, String name, String nickname, String avatar) {
        public Member {
            id = trim(id);
            name = trim(name);
            nickname = trim(nickname);
            avatar = trim(avatar);
        }
    }

    /**
     * 按 wxid 找人。首次未命中会强刷一次（新进群的人）。
     */
    public Member findById(String chatroom, String id) {
        if (!isChatroom(chatroom) || id == null || id.isBlank()) {
            return null;
        }
        String key = id.trim().toLowerCase(Locale.ROOT);
        Member hit = load(chatroom, false).byId().get(key);
        return hit != null ? hit : load(chatroom, true).byId().get(key);
    }

    /**
     * 按群里显示的名字找人。名字在群内不唯一时返回 {@code null}，不猜。
     */
    public Member findByName(String chatroom, String name) {
        if (!isChatroom(chatroom) || name == null || name.isBlank()) {
            return null;
        }
        String key = normalize(name);
        if (key.isEmpty()) {
            return null;
        }
        Member hit = load(chatroom, false).byName().get(key);
        return hit != null ? hit : load(chatroom, true).byName().get(key);
    }

    public static boolean isChatroom(String id) {
        return id != null && id.trim().endsWith("@chatroom");
    }

    private Snapshot load(String chatroom, boolean refresh) {
        String key = chatroom.trim();
        long now = System.currentTimeMillis();
        Snapshot cached = cache.get(key);
        if (cached != null) {
            long age = now - cached.fetchedAt();
            if (refresh ? age < MIN_REFRESH_MS : age < TTL_MS) {
                return cached;
            }
        }
        Snapshot loaded = fetch(key);
        if (loaded == null) {
            return cached == null ? Snapshot.empty(now) : cached;
        }
        cache.put(key, loaded);
        return loaded;
    }

    private Snapshot fetch(String chatroom) {
        try {
            JsonNode root = apiClient.getChatroomMembers(chatroom);
            Snapshot snapshot = parse(root, System.currentTimeMillis());
            log.info("Golem chatroom roster chatroom={} members={} names={}",
                    chatroom, snapshot.byId().size(), snapshot.byName().size());
            return snapshot;
        } catch (Exception e) {
            log.warn("Golem chatroom roster failed chatroom={}: {}", chatroom, e.toString());
            return null;
        }
    }

    /** 只认 {@code data.result.list[]}：username / nickname / display_name / big_avatar_url 均为字符串。 */
    static Snapshot parse(JsonNode root, long fetchedAt) {
        Map<String, Member> byId = new LinkedHashMap<>();
        Map<String, Member> byName = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        JsonNode list = root == null ? null : root.path("data").path("result").path("list");
        if (list == null || !list.isArray()) {
            return new Snapshot(Map.of(), Map.of(), fetchedAt);
        }
        for (JsonNode item : list) {
            String id = text(item, "username");
            if (id.isEmpty()) {
                continue;
            }
            String nickname = text(item, "nickname");
            String display = text(item, "display_name");
            String avatar = firstHttp(text(item, "big_avatar_url"), text(item, "small_avatar_url"));
            Member member = new Member(id, display.isEmpty() ? nickname : display, nickname, avatar);
            byId.put(id.toLowerCase(Locale.ROOT), member);
            index(byName, ambiguous, member.name(), member);
            index(byName, ambiguous, member.nickname(), member);
        }
        for (String key : ambiguous) {
            byName.remove(key);
        }
        return new Snapshot(Map.copyOf(byId), Map.copyOf(byName), fetchedAt);
    }

    private static void index(Map<String, Member> byName, Set<String> ambiguous, String name, Member member) {
        String key = normalize(name);
        if (key.isEmpty()) {
            return;
        }
        Member exists = byName.putIfAbsent(key, member);
        if (exists != null && !exists.id().equalsIgnoreCase(member.id())) {
            ambiguous.add(key);
        }
    }

    /** 昵称里混着零宽和特殊空白，比对前统一抹掉；只做精确匹配，不做模糊。 */
    static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String text = Normalizer.normalize(value, Normalizer.Form.NFKC);
        text = INVISIBLE.matcher(text).replaceAll("");
        return text.toLowerCase(Locale.ROOT);
    }

    record Snapshot(Map<String, Member> byId, Map<String, Member> byName, long fetchedAt) {
        static Snapshot empty(long fetchedAt) {
            return new Snapshot(Map.of(), Map.of(), fetchedAt);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isTextual() ? "" : value.asText("").trim();
    }

    private static String firstHttp(String... values) {
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                return value.trim();
            }
        }
        return "";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
