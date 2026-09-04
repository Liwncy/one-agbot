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
 * 把群 @ 的 wxid 配上正文里的展示名，并查通讯录补头像。
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
        Map<String, Profile> profiles = resolveProfiles(ids, msg.groupId());
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

    private Map<String, Profile> resolveProfiles(List<String> ids, String groupId) {
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
            mergeProfiles(out, fetched);
            remember(fetched);
        }
        if (needsAvatar(ids, out) && groupId != null && groupId.endsWith("@chatroom")) {
            Map<String, Profile> room = fetchProfiles(List.of(groupId));
            mergeProfiles(out, room);
            remember(room);
            if (needsAvatar(ids, out)) {
                Map<String, Profile> members = fetchChatroomMembers(groupId);
                mergeProfiles(out, members);
                remember(members);
            }
        }
        rememberRequested(ids, out);
        return out;
    }

    private void remember(Map<String, Profile> profiles) {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Profile> entry : profiles.entrySet()) {
            Profile profile = entry.getValue();
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
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

    private boolean needsAvatar(List<String> ids, Map<String, Profile> profiles) {
        for (String id : ids) {
            Profile profile = profiles.get(id.toLowerCase(Locale.ROOT));
            if (profile == null || profile.avatar().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Profile> fetchProfiles(List<String> usernames) {
        try {
            JsonNode root = apiClient.getContactDetail(usernames);
            return parseProfiles(root);
        } catch (Exception e) {
            log.warn("Golem contact detail failed ids={}: {}", usernames, e.toString());
            return Map.of();
        }
    }

    private Map<String, Profile> fetchChatroomMembers(String groupId) {
        try {
            JsonNode root = apiClient.getChatroomMembers(groupId);
            return parseProfiles(root);
        } catch (Exception e) {
            log.warn("Golem chatroom members failed groupId={}: {}", groupId, e.toString());
            return Map.of();
        }
    }

    private static Map<String, Profile> parseProfiles(JsonNode root) {
        Map<String, Profile> out = new LinkedHashMap<>();
        if (root == null || root.isNull() || root.isMissingNode()) {
            return out;
        }
        JsonNode data = root.has("data") && !root.get("data").isNull() ? root.get("data") : root;
        collectNode(data, out);
        return out;
    }

    private static void collectNode(JsonNode node, Map<String, Profile> out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectNode(child, out);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        Profile self = readProfile(node);
        if (!self.id().isBlank()) {
            out.put(self.id().toLowerCase(Locale.ROOT), self);
        }
        collectNode(node.get("contact_list"), out);
        collectNode(node.get("list"), out);
        JsonNode members = node.get("members");
        if (members != null && members.isObject()) {
            collectNode(members.get("list"), out);
        }
        collectNode(node.get("result"), out);
    }

    private static Profile readProfile(JsonNode node) {
        String id = firstNonBlank(
                unwrap(node, "username"),
                unwrap(node, "user_name"),
                unwrap(node, "wxid"),
                unwrap(node, "id"),
                unwrap(node, "userName"));
        String name = firstNonBlank(
                unwrap(node, "display_name"),
                unwrap(node, "nickname"),
                unwrap(node, "nick_name"));
        String avatar = firstNonBlank(
                unwrap(node, "big_avatar_url"),
                unwrap(node, "small_avatar_url"),
                unwrap(node, "avatar_url"),
                unwrap(node, "head_img_url"),
                unwrap(node, "headimgurl"),
                unwrap(node, "big_head_img_url"),
                unwrap(node, "small_head_img_url"));
        if (!looksLikeHttp(avatar)) {
            avatar = "";
        }
        return new Profile(id, name, avatar);
    }

    private static void mergeProfiles(Map<String, Profile> target, Map<String, Profile> extra) {
        for (Map.Entry<String, Profile> entry : extra.entrySet()) {
            Profile incoming = entry.getValue();
            Profile current = target.get(entry.getKey());
            if (current == null || current.avatar().isBlank()) {
                target.put(entry.getKey(), incoming);
            } else if (current.name().isBlank() && !incoming.name().isBlank()) {
                target.put(entry.getKey(), new Profile(current.id().isBlank() ? incoming.id() : current.id(),
                        incoming.name(), current.avatar()));
            }
        }
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

    private record Profile(String id, String name, String avatar) {
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
