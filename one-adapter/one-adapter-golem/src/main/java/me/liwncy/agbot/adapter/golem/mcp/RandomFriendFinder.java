package me.liwncy.agbot.adapter.golem.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import me.liwncy.agbot.adapter.golem.api.GolemApiClient;
import me.liwncy.agbot.common.core.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 随机手机号搜微信用户，对齐 xchatbot {@code random-friend}。
 */
final class RandomFriendFinder {
    private static final Logger log = LoggerFactory.getLogger(RandomFriendFinder.class);
    private static final int SEARCH_MAX_ATTEMPTS = 12;
    private static final int SEARCH_FROM_SCENE = 1;
    private static final int SEARCH_SCENE = 2;
    private static final int DEFAULT_CARD_SCENE = 17;
    private static final int MIN_QUALITY_SCORE = 4;
    private static final int[][] MOBILE_PREFIX_WEIGHTS = {
            {130, 1}, {131, 1}, {132, 1}, {133, 1}, {135, 1}, {136, 1}, {137, 1}, {138, 1}, {139, 1},
            {147, 2}, {150, 2}, {151, 2}, {152, 2}, {155, 2}, {156, 2}, {157, 2}, {158, 2}, {159, 2},
            {166, 4}, {167, 4}, {170, 2}, {171, 4}, {172, 4}, {173, 4}, {175, 4}, {176, 4}, {177, 4}, {178, 4},
            {180, 4}, {181, 4}, {182, 4}, {183, 4}, {184, 4}, {185, 4}, {186, 4}, {187, 4}, {188, 4}, {189, 4},
            {190, 5}, {191, 5}, {193, 5}, {195, 5}, {196, 5}, {197, 5}, {198, 5}, {199, 5}
    };

    private final GolemApiClient apiClient;

    RandomFriendFinder(GolemApiClient apiClient) {
        this.apiClient = apiClient;
    }

    record Candidate(
            String username,
            String nickname,
            String alias,
            String province,
            String city,
            String sign,
            int gender,
            int verifyFlag,
            String country,
            String bigAvatarUrl,
            String smallAvatarUrl,
            String antispamTicket,
            int scene,
            int matchType
    ) {
        String avatarUrl() {
            return !smallAvatarUrl.isBlank() ? smallAvatarUrl : bigAvatarUrl;
        }
    }

    record Hit(Candidate candidate, String phone, int attempts) {
    }

    Hit search() {
        String lastPhone = "";
        for (int attempt = 1; attempt <= SEARCH_MAX_ATTEMPTS; attempt++) {
            String phone = randomPhone();
            lastPhone = phone;
            JsonNode root = apiClient.searchContacts(phone, SEARCH_FROM_SCENE, SEARCH_SCENE);
            int code = root.path("code").asInt(0);
            String message = root.path("message").asText("");
            if (isNotFound(code, message)) {
                log.debug("random-friend miss attempt={} phone={}", attempt, phone);
                continue;
            }
            if (code != 0) {
                throw new ServiceException("Golem searchContacts: " + message);
            }
            Candidate candidate = pickCandidate(root.path("data"));
            if (candidate != null) {
                return new Hit(candidate, phone, attempt);
            }
        }
        return new Hit(null, lastPhone, SEARCH_MAX_ATTEMPTS);
    }

    private static boolean isNotFound(int code, String message) {
        if (code != -1) {
            return false;
        }
        return message.contains("用户不存在")
                || message.contains("被搜账号状态异常")
                || message.contains("无法显示");
    }

    private static Candidate pickCandidate(JsonNode data) {
        for (JsonNode entry : contactEntries(data)) {
            Candidate candidate = normalize(entry);
            if (candidate == null) {
                continue;
            }
            Quality quality = evaluate(candidate);
            if (quality.passed) {
                return candidate;
            }
            log.debug("random-friend skip username={} nick={} score={} reasons={}",
                    candidate.username(), candidate.nickname(), quality.score, quality.reasons);
        }
        return null;
    }

    private static List<JsonNode> contactEntries(JsonNode data) {
        List<JsonNode> entries = new ArrayList<>();
        if (data == null || data.isNull() || data.isMissingNode()) {
            return entries;
        }
        JsonNode list = data.path("contact_list");
        if (list.isArray()) {
            for (JsonNode item : list) {
                if (item != null && item.isObject()) {
                    entries.add(item);
                }
            }
            return entries;
        }
        if (data.isObject() && (data.has("username") || data.has("nickname") || data.has("antispam_ticket"))) {
            entries.add(data);
        }
        return entries;
    }

    private static Candidate normalize(JsonNode entry) {
        String username = unwrap(entry, "username");
        String nickname = unwrap(entry, "nickname");
        if (username.isBlank() || nickname.isBlank()) {
            return null;
        }
        return new Candidate(
                username,
                nickname,
                unwrap(entry, "alias"),
                unwrap(entry, "province"),
                unwrap(entry, "city"),
                firstNonBlank(unwrap(entry, "signature"), unwrap(entry, "sign")),
                toInt(entry, "gender"),
                toInt(entry, "verify_flag"),
                unwrap(entry, "country"),
                unwrap(entry, "big_avatar_url"),
                unwrap(entry, "small_avatar_url"),
                unwrap(entry, "antispam_ticket"),
                defaultScene(toInt(entry, "scene")),
                toInt(entry, "match_type")
        );
    }

    private record Quality(boolean passed, int score, List<String> reasons) {
    }

    private static Quality evaluate(Candidate candidate) {
        int score = 0;
        boolean blocked = false;
        List<String> reasons = new ArrayList<>();
        if (candidate.username().endsWith("@chatroom")) {
            blocked = true;
            reasons.add("命中群聊账号");
        }
        String nickname = candidate.nickname().trim();
        if (nickname.isEmpty()) {
            reasons.add("昵称为空");
        } else if (isPhoneLike(nickname) || isWxidLike(nickname)) {
            reasons.add("昵称像占位标识");
        } else {
            score += 2;
        }
        if (!candidate.bigAvatarUrl().isBlank() || !candidate.smallAvatarUrl().isBlank()) {
            score += 1;
        } else {
            reasons.add("缺少头像");
        }
        if (!candidate.antispamTicket().isBlank()) {
            score += 1;
        } else {
            reasons.add("缺少名片票据");
        }
        if (!candidate.alias().isBlank()) {
            score += 1;
        }
        if (!candidate.sign().isBlank()) {
            score += 1;
        }
        if (!candidate.province().isBlank() || !candidate.city().isBlank() || !candidate.country().isBlank()) {
            score += 1;
        } else {
            reasons.add("地区资料过少");
        }
        if (candidate.verifyFlag() > 0) {
            score += 1;
        }
        return new Quality(!blocked && score >= MIN_QUALITY_SCORE, score, reasons);
    }

    private static boolean isPhoneLike(String value) {
        return value.matches("^1\\d{10}$");
    }

    private static boolean isWxidLike(String value) {
        return value.matches("(?i)^wxid[_a-zA-Z0-9-]+$") || value.matches("(?i)^v3_.*");
    }

    private static String randomPhone() {
        return pickPrefix() + randomDigits(8);
    }

    private static String pickPrefix() {
        int total = 0;
        for (int[] item : MOBILE_PREFIX_WEIGHTS) {
            total += item[1];
        }
        int hit = ThreadLocalRandom.current().nextInt(total);
        for (int[] item : MOBILE_PREFIX_WEIGHTS) {
            if (hit < item[1]) {
                return String.valueOf(item[0]);
            }
            hit -= item[1];
        }
        return "188";
    }

    private static String randomDigits(int length) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    static String unwrap(JsonNode parent, String field) {
        if (parent == null) {
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

    private static int toInt(JsonNode parent, String field) {
        JsonNode node = parent == null ? null : parent.get(field);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return 0;
        }
        if (node.isNumber()) {
            return node.asInt(0);
        }
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText().trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private static int defaultScene(int scene) {
        return scene > 0 ? scene : DEFAULT_CARD_SCENE;
    }

    private static String firstNonBlank(String left, String right) {
        return left == null || left.isBlank() ? (right == null ? "" : right) : left;
    }
}
