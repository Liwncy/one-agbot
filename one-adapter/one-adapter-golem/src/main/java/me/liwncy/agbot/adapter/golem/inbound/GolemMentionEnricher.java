package me.liwncy.agbot.adapter.golem.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import me.liwncy.agbot.adapter.golem.api.GolemApiClient;
import me.liwncy.agbot.kernel.api.message.ChannelExtraKeys;
import me.liwncy.agbot.kernel.api.message.MsgInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 群 @ 用 {@code POST /api/contacts/detail} 补 wxid 对应的昵称和头像。
 */
public class GolemMentionEnricher {
    private static final Logger log = LoggerFactory.getLogger(GolemMentionEnricher.class);
    private static final long HIT_TTL_MS = 30 * 60 * 1000L;
    private static final long MISS_TTL_MS = 2 * 60 * 1000L;

    private final GolemApiClient apiClient;
    private final ConcurrentHashMap<String, CachedProfile> cache = new ConcurrentHashMap<>();

    public GolemMentionEnricher(GolemApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public MsgInfo enrich(MsgInfo msg) {
        if (msg == null || msg.isPrivateChat()) {
            return msg;
        }
        Map<String, Object> extra = msg.extra() == null ? Map.of() : msg.extra();
        List<String> ids = mentionIds(extra.get(ChannelExtraKeys.MENTION_IDS));
        if (ids.isEmpty()) {
            return msg;
        }
        List<String> names = GolemMentionDetector.extractAtDisplayNames(msg.msg());
        Map<String, Profile> profiles = resolveProfiles(ids);
        List<Map<String, String>> mentions = new ArrayList<>();
        int nameIdx = 0;
        int withAvatar = 0;
        int seq = 1;
        for (String id : ids) {
            String fromText = nameIdx < names.size() ? names.get(nameIdx++) : "";
            Profile profile = profiles.getOrDefault(id.toLowerCase(Locale.ROOT), Profile.EMPTY);
            String name = firstNonBlank(profile.name(), fromText, id);
            String avatar = profile.avatar();
            if (!avatar.isBlank()) {
                withAvatar++;
            }
            Map<String, String> row = new LinkedHashMap<>();
            row.put("seq", String.valueOf(seq++));
            row.put("id", id);
            row.put("name", name);
            row.put("avatar", avatar);
            mentions.add(row);
        }
        Map<String, Object> next = new HashMap<>(extra);
        next.put(ChannelExtraKeys.MENTIONS, mentions);
        log.info("Golem mention enrich groupId={} count={} avatars={} ids={}",
                msg.groupId(), mentions.size(), withAvatar, ids);
        return new MsgInfo(
                msg.platform(),
                msg.accountId(),
                msg.userId(),
                msg.userName(),
                msg.groupId(),
                msg.groupName(),
                msg.msg(),
                msg.msgId(),
                msg.fromType(),
                msg.msgType(),
                msg.path(),
                msg.replyToMsgId(),
                msg.createTime(),
                Map.copyOf(next)
        );
    }

    private Map<String, Profile> resolveProfiles(List<String> ids) {
        Map<String, Profile> out = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String id : ids) {
            CachedProfile cached = cache.get(id.toLowerCase(Locale.ROOT));
            if (cached != null && cached.expireAt() > now) {
                out.put(id.toLowerCase(Locale.ROOT), cached.profile());
            } else {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            Map<String, Profile> fetched = fetchProfiles(missing);
            out.putAll(fetched);
            remember(fetched);
        }
        rememberRequested(ids, out);
        return out;
    }

    private void remember(Map<String, Profile> profiles) {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Profile> entry : profiles.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            Profile profile = entry.getValue();
            cache.put(entry.getKey(), new CachedProfile(
                    profile, now + (profile.avatar().isBlank() ? MISS_TTL_MS : HIT_TTL_MS)));
        }
    }

    private void rememberRequested(List<String> ids, Map<String, Profile> profiles) {
        long now = System.currentTimeMillis();
        for (String id : ids) {
            String key = id.toLowerCase(Locale.ROOT);
            Profile profile = profiles.getOrDefault(key, Profile.EMPTY);
            cache.put(key, new CachedProfile(
                    profile, now + (profile.avatar().isBlank() ? MISS_TTL_MS : HIT_TTL_MS)));
        }
    }

    private Map<String, Profile> fetchProfiles(List<String> usernames) {
        try {
            JsonNode root = apiClient.getContactDetail(usernames);
            Map<String, Profile> parsed = parseContactList(root);
            log.info("Golem contact detail ids={} code={} parsed={} avatars={}",
                    usernames, root.path("code").asInt(0), parsed.size(), countAvatars(parsed));
            return parsed;
        } catch (Exception e) {
            log.warn("Golem contact detail failed ids={}: {}", usernames, e.toString());
            return Map.of();
        }
    }

    private static int countAvatars(Map<String, Profile> profiles) {
        int n = 0;
        for (Profile profile : profiles.values()) {
            if (profile != null && !profile.avatar().isBlank()) {
                n++;
            }
        }
        return n;
    }

    /** 只认 {@code data.contact_list[]}：username/nickname 为 {value}，头像为字符串 URL。 */
    static Map<String, Profile> parseContactList(JsonNode root) {
        Map<String, Profile> out = new LinkedHashMap<>();
        if (root == null || root.isNull() || root.isMissingNode()) {
            return out;
        }
        JsonNode data = root.has("data") && !root.get("data").isNull() ? root.get("data") : root;
        JsonNode list = data.path("contact_list");
        if (!list.isArray()) {
            return out;
        }
        for (JsonNode item : list) {
            Profile profile = readProfile(item);
            if (!profile.id().isBlank()) {
                out.put(profile.id().toLowerCase(Locale.ROOT), profile);
            }
        }
        return out;
    }

    private static Profile readProfile(JsonNode node) {
        String id = unwrap(node, "username");
        String name = firstNonBlank(unwrap(node, "nickname"), unwrap(node, "display_name"));
        String avatar = firstNonBlank(unwrap(node, "big_avatar_url"), unwrap(node, "small_avatar_url"));
        if (!looksLikeHttp(avatar)) {
            avatar = "";
        }
        return new Profile(id, name, avatar);
    }

    static List<String> mentionIds(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> ids = new ArrayList<>();
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                String id = String.valueOf(item).trim();
                if (!id.isEmpty() && !"notify@all".equalsIgnoreCase(id)) {
                    ids.add(id);
                }
            }
            return ids;
        }
        if (raw instanceof String text && !text.isBlank()) {
            List<String> ids = new ArrayList<>();
            for (String part : text.split("[,;，；\\s]+")) {
                String id = part.trim();
                if (!id.isEmpty() && !"notify@all".equalsIgnoreCase(id)) {
                    ids.add(id);
                }
            }
            return ids;
        }
        return List.of();
    }

    private static String unwrap(JsonNode parent, String field) {
        if (parent == null || field == null) {
            return "";
        }
        return unwrapNode(parent.get(field));
    }

    private static String unwrapNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        if (node.isTextual() || node.isNumber()) {
            return node.asText("").trim();
        }
        if (node.isObject()) {
            JsonNode value = node.get("value");
            if (value != null && (value.isTextual() || value.isNumber())) {
                return value.asText("").trim();
            }
        }
        return "";
    }

    private static boolean looksLikeHttp(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    record Profile(String id, String name, String avatar) {
        static final Profile EMPTY = new Profile("", "", "");

        Profile {
            id = id == null ? "" : id.trim();
            name = name == null ? "" : name.trim();
            avatar = avatar == null ? "" : avatar.trim();
        }
    }

    private record CachedProfile(Profile profile, long expireAt) {
    }
}
