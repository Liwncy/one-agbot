package me.liwncy.agbot.adapter.golem.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import me.liwncy.agbot.adapter.golem.GolemAdapter;
import me.liwncy.agbot.adapter.golem.GolemProperties;
import me.liwncy.agbot.common.json.JsonUtils;
import me.liwncy.agbot.kernel.api.message.ChannelExtraKeys;
import me.liwncy.agbot.kernel.api.message.MediaRef;
import me.liwncy.agbot.kernel.api.message.MsgInfo;
import me.liwncy.agbot.kernel.api.message.MsgType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 Golem 推送信封归一为 {@link MsgInfo}（全类型进契约；对齐 xchatbot parse-payload）。
 */
public final class GolemMessageParser {
    private static final Logger log = LoggerFactory.getLogger(GolemMessageParser.class);

    /** 允许属性名与 =、引号之间有空格（群聊表情常见：cdnurl = "http://..."）。 */
    private static final Pattern XML_ATTR = Pattern.compile("([\\w:]+)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern APPMSG_TYPE = Pattern.compile("(?is)<type>(\\d+)</type>");
    private static final Pattern APPMSG_TITLE = Pattern.compile("(?is)<title>(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?</title>");
    private static final Pattern APPMSG_URL = Pattern.compile("(?is)<url>(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?</url>");
    private static final Pattern REFER_SVRID = Pattern.compile("(?is)<svrid>(\\d+)</svrid>");
    private static final Pattern REFER_CONTENT = Pattern.compile("(?is)<content>(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?</content>");
    private static final Pattern REFER_TYPE = Pattern.compile("(?is)<refermsg>.*?<type>(\\d+)</type>", Pattern.DOTALL);

    private GolemMessageParser() {
    }

    public static List<MsgInfo> parse(String accountId, String rawJson, GolemProperties properties) {
        JsonNode root = JsonUtils.fromJson(rawJson, JsonNode.class);
        JsonNode items = root == null ? null : root.get("new_message");
        if (items == null || !items.isArray() || items.isEmpty()) {
            return List.of();
        }
        List<MsgInfo> result = new ArrayList<>();
        for (JsonNode item : items) {
            MsgInfo msg = parseItem(accountId, item, properties);
            if (msg != null) {
                result.add(msg);
            }
        }
        return result;
    }

    private static MsgInfo parseItem(String accountId, JsonNode item, GolemProperties properties) {
        int wechatType = item.path("type").asInt(0);
        String source = inferSource(item);
        if ("official".equals(source) && !properties.isAllowOfficial()) {
            log.debug("Skip official message accountId={}", accountId);
            return null;
        }

        String sender = textValue(item, "sender");
        String receiver = textValue(item, "receiver");
        String rawContent = firstNonBlank(
                textValue(item, "content"),
                item.path("content").path("value").asText(null),
                item.path("content").path("string").asText(null),
                item.path("content").asText(null),
                ""
        );
        if (rawContent.isBlank()) {
            rawContent = item.path("push_content").asText("");
        }
        String pushContent = item.path("push_content").asText("");
        String msgSource = firstNonBlank(
                textValue(item, "msg_source"),
                asTextOrNull(item, "msg_source"),
                collectMentionSource(item),
                ""
        );
        String userName = parseSenderName(pushContent);

        String groupId = "0";
        String userId;
        String bodyXml;
        if ("group".equals(source)) {
            groupId = resolveRoomId(sender, receiver);
            GroupText groupText = parseGroupTextSender(rawContent);
            userId = groupText.senderId();
            if (userId == null || userId.isBlank()) {
                userId = sender != null && !sender.endsWith("@chatroom") ? sender : "";
            }
            bodyXml = groupText.content();
        } else {
            userId = sender == null ? "" : sender;
            bodyXml = rawContent;
        }

        // 好友申请
        if (wechatType == 37) {
            if (userId.isBlank()) {
                userId = "EventFriendVerify";
            }
            Map<String, Object> friendExtra = baseExtra(source, receiver, pushContent, msgSource, false, wechatType);
            friendExtra.put("event", "friend_verify");
            return new MsgInfo(
                    GolemAdapter.PLATFORM, accountId, userId,
                    firstNonBlank(xmlAttr(bodyXml, "fromnickname"), userName),
                    "0", null, bodyXml,
                    buildMsgId(item, receiver, userId, groupId),
                    "Friend", MsgType.TEXT, null, null,
                    toEpochMillis(item.path("create_time").asLong(0)),
                    Map.copyOf(friendExtra)
            );
        }

        if (userId == null || userId.isBlank()) {
            log.info("Skip golem message without userId accountId={} type={} sender={} push={}",
                    accountId, wechatType, sender, preview(pushContent));
            return null;
        }

        MappedContent mapped = mapContent(wechatType, bodyXml, pushContent, item);
        String content = mapped.msg();
        String msgType = mapped.msgType();
        String path = mapped.path();
        String replyToMsgId = mapped.replyToMsgId();
        Map<String, Object> typeExtra = mapped.extra();

        boolean botMentioned = GolemMentionDetector.isBotMentioned(
                content,
                pushContent,
                msgSource,
                properties.getBotWechatId(),
                properties.getBotWechatName());
        if ("group".equals(source) && botMentioned && MsgType.TEXT.equals(msgType)) {
            content = GolemMentionDetector.stripMentionPrefix(
                    content, properties.getBotWechatId(), properties.getBotWechatName());
            if (content.isBlank()) {
                content = "你好";
            }
        }

        if (content == null || content.isBlank()) {
            content = defaultLabel(msgType);
        }

        Map<String, Object> extra = baseExtra(source, receiver, pushContent, msgSource, botMentioned, wechatType);
        extra.putAll(typeExtra);
        if (item.has("new_id") || item.has("new_msg_id")) {
            extra.put("newId", firstNonBlank(asTextOrNull(item, "new_id"), asTextOrNull(item, "new_msg_id")));
        }
        // 显式标注传输形态：当前多为平台 CDN/缓冲，后续适配器可升级为 URL/FILE/BASE64
        String resolvedPath = annotateMediaForm(msgType, path, extra);

        MsgInfo msg = new MsgInfo(
                GolemAdapter.PLATFORM,
                accountId,
                userId,
                userName,
                groupId,
                null,
                content,
                buildMsgId(item, receiver, userId, groupId),
                "Social",
                msgType,
                resolvedPath,
                replyToMsgId,
                toEpochMillis(item.path("create_time").asLong(0)),
                Map.copyOf(extra)
        );
        logInboundMedia(msg, wechatType, bodyXml);
        return msg;
    }

    /** 媒体入站：打印 XML 关键字段，便于核对是否为可直链的 http(s)。 */
    private static void logInboundMedia(MsgInfo msg, int wechatType, String bodyXml) {
        String type = MsgType.normalize(msg.msgType());
        if (!MsgType.IMAGE.equals(type)
                && !MsgType.VIDEO.equals(type)
                && !MsgType.AUDIO.equals(type)
                && !MsgType.FILE.equals(type)
                && !MsgType.EMOJI.equals(type)) {
            return;
        }
        MediaRef ref = MediaRef.fromMsg(msg);
        String cdnMid = xmlAttr(bodyXml, "cdnmidimgurl");
        String cdnBig = xmlAttr(bodyXml, "cdnbigimgurl");
        String cdnVideo = firstNonBlank(xmlAttr(bodyXml, "cdnvideourl"), xmlAttr(bodyXml, "cdndataurl"));
        String cdnUrl = xmlAttr(bodyXml, "cdnurl");
        String voiceUrl = firstNonBlank(xmlAttr(bodyXml, "voiceurl"), xmlAttr(bodyXml, "voiceUrl"));
        String aes = xmlAttr(bodyXml, "aeskey");
        String locator = firstNonBlank(
                ref == null ? null : ref.path(),
                ref == null ? null : ref.platformId(),
                msg.path()
        );
        log.info("Golem inbound media wechatType={} msgType={} form={} locator={} http={} aeskey={} "
                        + "cdnmid={} cdnbig={} cdnvideo={} cdnurl={} voiceurl={} xml={}",
                wechatType,
                type,
                ref == null ? "-" : ref.form(),
                preview(locator, 120),
                looksLikeHttp(locator),
                aes.isBlank() ? "-" : "present",
                preview(cdnMid, 120),
                preview(cdnBig, 120),
                preview(cdnVideo, 120),
                preview(cdnUrl, 120),
                preview(voiceUrl, 120),
                preview(bodyXml, 500));
    }

    private static String annotateMediaForm(String msgType, String path, Map<String, Object> extra) {
        String type = MsgType.normalize(msgType);
        boolean mediaType = MsgType.IMAGE.equals(type)
                || MsgType.VIDEO.equals(type)
                || MsgType.AUDIO.equals(type)
                || MsgType.FILE.equals(type)
                || MsgType.EMOJI.equals(type);
        if (!mediaType) {
            return path;
        }
        MediaRef ref = MediaRef.from(path, extra);
        if (ref == null) {
            return path;
        }
        return ref.applyToExtra(extra);
    }

    private static MappedContent mapContent(int wechatType, String bodyXml, String pushContent, JsonNode item) {
        String mapped = GolemWechatTypeMapper.toMsgType(wechatType);
        String textBody = resolveTextContent(bodyXml, pushContent);
        Map<String, Object> extra = new HashMap<>();

        if (mapped == null) {
            log.info("Unknown wechat type={}, pass as text placeholder", wechatType);
            return new MappedContent(MsgType.TEXT,
                    firstNonBlank(textBody, "[未知消息 type=" + wechatType + "]"),
                    null, null, extra);
        }

        return switch (mapped) {
            case MsgType.TEXT -> new MappedContent(MsgType.TEXT, textBody, null, null, extra);
            case MsgType.IMAGE -> {
                String buffer = imageBufferHint(item);
                String cdn = firstNonBlank(
                        xmlAttr(bodyXml, "cdnmidimgurl"),
                        xmlAttr(bodyXml, "cdnbigimgurl"),
                        xmlAttr(bodyXml, "cdnthumburl")
                );
                String aes = xmlAttr(bodyXml, "aeskey");
                if (!aes.isBlank()) {
                    extra.put("aeskey", aes);
                }
                putIfPresent(extra, "length", firstNonBlank(xmlAttr(bodyXml, "length"), xmlAttr(bodyXml, "hdlength")));
                if (!buffer.isBlank()) {
                    MediaRef ref = MediaRef.base64(buffer, "image/jpeg");
                    String path = ref.applyToExtra(extra);
                    yield new MappedContent(MsgType.IMAGE, firstNonBlank(parsePreviewText(pushContent), "[图片]"),
                            path, null, extra);
                }
                if (!cdn.isBlank()) {
                    MediaRef ref = mediaRefOfLocator(cdn);
                    String path = ref.applyToExtra(extra);
                    yield new MappedContent(MsgType.IMAGE, firstNonBlank(parsePreviewText(pushContent), "[图片]"),
                            path, null, extra);
                }
                yield new MappedContent(MsgType.IMAGE, firstNonBlank(parsePreviewText(pushContent), "[图片]"),
                        null, null, extra);
            }
            case MsgType.AUDIO -> {
                String path = firstNonBlank(xmlAttr(bodyXml, "voiceurl"), xmlAttr(bodyXml, "voiceUrl"));
                putIfPresent(extra, ChannelExtraKeys.DURATION,
                        firstNonBlank(xmlAttr(bodyXml, "voicelength"), xmlAttr(bodyXml, "playlength")));
                putIfPresent(extra, ChannelExtraKeys.FORMAT, xmlAttr(bodyXml, "voiceformat"));
                putIfPresent(extra, "length", firstNonBlank(xmlAttr(bodyXml, "length"),
                        xmlAttr(bodyXml, "voicelength")));
                putIfPresent(extra, "bufferId", xmlAttr(bodyXml, "bufid"));
                putIfPresent(extra, "aeskey", xmlAttr(bodyXml, "aeskey"));
                String resolved = blankToNull(path);
                if (resolved != null) {
                    resolved = mediaRefOfLocator(resolved).applyToExtra(extra);
                }
                yield new MappedContent(MsgType.AUDIO, firstNonBlank(parsePreviewText(pushContent), "[语音]"),
                        resolved, null, extra);
            }
            case MsgType.VIDEO -> {
                String path = firstNonBlank(
                        xmlAttr(bodyXml, "cdnvideourl"),
                        xmlAttr(bodyXml, "cdndataurl"),
                        xmlAttr(bodyXml, "cdnurl")
                );
                putIfPresent(extra, ChannelExtraKeys.THUMB, xmlAttr(bodyXml, "cdnthumburl"));
                putIfPresent(extra, ChannelExtraKeys.DURATION,
                        firstNonBlank(xmlAttr(bodyXml, "playlength"), xmlAttr(bodyXml, "duration")));
                putIfPresent(extra, "aeskey", firstNonBlank(xmlAttr(bodyXml, "aeskey"),
                        xmlAttr(bodyXml, "cdnvideosaeskey")));
                putIfPresent(extra, "length", firstNonBlank(xmlAttr(bodyXml, "length"),
                        xmlAttr(bodyXml, "playlength")));
                String resolved = blankToNull(path);
                if (resolved != null) {
                    resolved = mediaRefOfLocator(resolved).applyToExtra(extra);
                }
                yield new MappedContent(MsgType.VIDEO, firstNonBlank(parsePreviewText(pushContent), "[视频]"),
                        resolved, null, extra);
            }
            case MsgType.EMOJI -> {
                String path = firstNonBlank(xmlAttr(bodyXml, "cdnurl"), xmlAttr(bodyXml, "emoji_url"));
                String md5 = xmlAttr(bodyXml, "md5");
                if (!md5.isBlank()) {
                    extra.put(ChannelExtraKeys.MD5, md5);
                }
                putIfPresent(extra, "aeskey", xmlAttr(bodyXml, "aeskey"));
                String resolved = blankToNull(path);
                if (resolved != null) {
                    resolved = mediaRefOfLocator(resolved).applyToExtra(extra);
                }
                yield new MappedContent(MsgType.EMOJI, firstNonBlank(parsePreviewText(pushContent), "[表情]"),
                        resolved, null, extra);
            }
            case MsgType.POSITION -> {
                putIfPresent(extra, ChannelExtraKeys.LAT, xmlAttr(bodyXml, "x"));
                putIfPresent(extra, ChannelExtraKeys.LON, xmlAttr(bodyXml, "y"));
                putIfPresent(extra, ChannelExtraKeys.LABEL, xmlAttr(bodyXml, "label"));
                putIfPresent(extra, ChannelExtraKeys.POI_NAME, xmlAttr(bodyXml, "poiname"));
                putIfPresent(extra, ChannelExtraKeys.SCALE, xmlAttr(bodyXml, "scale"));
                yield new MappedContent(MsgType.POSITION,
                        firstNonBlank(xmlAttr(bodyXml, "poiname"), xmlAttr(bodyXml, "label"), "[位置]"),
                        null, null, extra);
            }
            case MsgType.CARD -> {
                putIfPresent(extra, ChannelExtraKeys.CARD_USERNAME, xmlAttr(bodyXml, "username"));
                putIfPresent(extra, ChannelExtraKeys.CARD_NICKNAME, xmlAttr(bodyXml, "nickname"));
                putIfPresent(extra, ChannelExtraKeys.CARD_ALIAS, xmlAttr(bodyXml, "alias"));
                yield new MappedContent(MsgType.CARD,
                        firstNonBlank(xmlAttr(bodyXml, "nickname"), xmlAttr(bodyXml, "username"), "[名片]"),
                        null, null, extra);
            }
            case MsgType.APP -> mapAppMsg(bodyXml, pushContent, extra);
            default -> new MappedContent(mapped,
                    firstNonBlank(textBody, defaultLabel(mapped)), null, null, extra);
        };
    }

    /**
     * type=49 appmsg：引用(57)→text+quote；链接(5)→link；文件(6)→file；其余保留 app。
     */
    private static MappedContent mapAppMsg(String bodyXml, String pushContent, Map<String, Object> extra) {
        String appType = matchGroup(APPMSG_TYPE, bodyXml);
        putIfPresent(extra, ChannelExtraKeys.APP_TYPE, appType);
        String title = decodeXml(matchGroup(APPMSG_TITLE, bodyXml));
        String url = decodeXml(matchGroup(APPMSG_URL, bodyXml));

        if ("57".equals(appType)) {
            String referType = matchGroup(REFER_TYPE, bodyXml);
            String referContent = decodeXml(matchGroup(REFER_CONTENT, bodyXml));
            String svrid = matchGroup(REFER_SVRID, bodyXml);
            putIfPresent(extra, ChannelExtraKeys.QUOTE_MSG_TYPE, referType);
            putIfPresent(extra, ChannelExtraKeys.QUOTE_CONTENT, firstNonBlank(referContent, "[引用]"));
            String msg = firstNonBlank(title, parsePreviewText(pushContent), "你好");
            return new MappedContent(MsgType.TEXT, msg, null, blankToNull(svrid), extra);
        }
        if ("5".equals(appType) || "33".equals(appType) || "36".equals(appType)) {
            if (!url.isBlank()) {
                extra.put("url", url);
            }
            return new MappedContent(MsgType.LINK, firstNonBlank(title, "[链接]"), blankToNull(url), null, extra);
        }
        if ("6".equals(appType)) {
            String fileUrl = firstNonBlank(url, xmlAttr(bodyXml, "cdnattachurl"));
            return new MappedContent(MsgType.FILE, firstNonBlank(title, "[文件]"), blankToNull(fileUrl), null, extra);
        }
        return new MappedContent(MsgType.APP,
                firstNonBlank(title, parsePreviewText(pushContent), "[应用消息]"),
                blankToNull(url), null, extra);
    }

    private static Map<String, Object> baseExtra(String source, String receiver, String pushContent,
                                                 String msgSource, boolean botMentioned, int wechatType) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("source", source);
        extra.put("receiver", receiver == null ? "" : receiver);
        extra.put("botMentioned", botMentioned);
        extra.put("pushContent", pushContent == null ? "" : pushContent);
        extra.put("msgSource", msgSource == null ? "" : msgSource);
        extra.put("wechatType", wechatType);
        return extra;
    }

    private static String buildMsgId(JsonNode item, String receiver, String userId, String groupId) {
        String newId = firstNonBlank(asTextOrNull(item, "new_id"), asTextOrNull(item, "new_msg_id"));
        String clientId = firstNonBlank(asTextOrNull(item, "id"), asTextOrNull(item, "msg_id"), "0");
        String createTime = String.valueOf(item.path("create_time").asLong(0));
        String peer = (groupId != null && !groupId.isBlank() && !"0".equals(groupId))
                ? (groupId.contains("@chatroom") ? groupId : groupId + "@chatroom")
                : firstNonBlank(userId, receiver);
        if (!newId.isBlank()) {
            return newId + ":" + clientId + ":" + createTime + ":" + peer;
        }
        return firstNonBlank(clientId, createTime, String.valueOf(System.currentTimeMillis()));
    }

    private static String imageBufferHint(JsonNode item) {
        JsonNode buffer = item.path("image_buffer");
        if (buffer.isMissingNode() || buffer.isNull()) {
            return "";
        }
        String data = firstNonBlank(
                buffer.path("data").asText(null),
                buffer.path("buffer").asText(null),
                buffer.isTextual() ? buffer.asText(null) : null
        );
        return data == null ? "" : data.trim();
    }

    private static String defaultLabel(String msgType) {
        return switch (MsgType.normalize(msgType)) {
            case MsgType.IMAGE -> "[图片]";
            case MsgType.VIDEO -> "[视频]";
            case MsgType.AUDIO -> "[语音]";
            case MsgType.FILE -> "[文件]";
            case MsgType.EMOJI -> "[表情]";
            case MsgType.LINK -> "[链接]";
            case MsgType.CARD -> "[名片]";
            case MsgType.POSITION -> "[位置]";
            case MsgType.APP -> "[应用消息]";
            case MsgType.FORWARD -> "[转发消息]";
            default -> "[消息]";
        };
    }

    private static String inferSource(JsonNode item) {
        String sourceHint = firstNonBlank(asTextOrNull(item, "source"), "").toLowerCase();
        String sender = textValue(item, "sender");
        String receiver = textValue(item, "receiver");
        if (sourceHint.contains("official")) {
            return "official";
        }
        if (sourceHint.contains("chatroom")
                || "group".equals(sourceHint)
                || (sender != null && sender.endsWith("@chatroom"))
                || (receiver != null && receiver.endsWith("@chatroom"))) {
            return "group";
        }
        return "private";
    }

    private static String resolveRoomId(String sender, String receiver) {
        if (receiver != null && receiver.endsWith("@chatroom")) {
            return receiver;
        }
        if (sender != null && sender.endsWith("@chatroom")) {
            return sender;
        }
        return receiver == null ? "0" : receiver;
    }

    private static GroupText parseGroupTextSender(String rawContent) {
        String text = rawContent == null ? "" : rawContent;
        int nl = text.indexOf(":\n");
        int crlf = text.indexOf(":\r\n");
        int idx = nl > 0 ? nl : crlf;
        int sepLen = nl > 0 ? 2 : 3;
        if (idx <= 0) {
            return new GroupText(null, text);
        }
        String senderId = text.substring(0, idx).trim();
        String content = text.substring(idx + sepLen);
        if (senderId.isEmpty()) {
            return new GroupText(null, text);
        }
        return new GroupText(senderId, content);
    }

    private static String parseSenderName(String pushContent) {
        if (pushContent == null || pushContent.isBlank()) {
            return null;
        }
        for (String sep : List.of(" : ", ": ", "：", ":")) {
            int i = pushContent.indexOf(sep);
            if (i <= 0) {
                continue;
            }
            String name = pushContent.substring(0, i).trim();
            if (name.isEmpty() || name.contains("@chatroom") || name.regionMatches(true, 0, "wxid_", 0, 5)) {
                continue;
            }
            return name;
        }
        Matcher m = Pattern.compile("^(.+?)在群聊中").matcher(pushContent);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    private static String resolveTextContent(String content, String pushContent) {
        String text = content == null ? "" : content;
        if (!isUnsupportedClientContent(text)) {
            return text;
        }
        String preview = parsePreviewText(pushContent);
        return preview == null || preview.isBlank() ? text : preview;
    }

    private static String parsePreviewText(String pushContent) {
        if (pushContent == null || pushContent.isBlank()) {
            return null;
        }
        for (String sep : List.of(" : ", ": ", "：", ":")) {
            int i = pushContent.indexOf(sep);
            if (i <= 0) {
                continue;
            }
            return pushContent.substring(i + sep.length()).trim();
        }
        return pushContent.trim();
    }

    private static boolean isUnsupportedClientContent(String content) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            return false;
        }
        return text.contains("无法显示此消息");
    }

    private static long toEpochMillis(long ts) {
        if (ts <= 0) {
            return System.currentTimeMillis();
        }
        return ts > 1_000_000_000_000L ? ts : ts * 1000L;
    }

    private static String textValue(JsonNode item, String field) {
        JsonNode node = item.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        String value = firstNonBlank(
                node.path("value").asText(null),
                node.path("string").asText(null),
                node.path("String").asText(null),
                ""
        );
        return value.isBlank() ? null : value;
    }

    private static String asTextOrNull(JsonNode item, String field) {
        JsonNode node = item.get(field);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject() || node.isArray()) {
            return null;
        }
        String text = node.asText(null);
        return text == null || text.isBlank() ? null : text;
    }

    private static String collectMentionSource(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        collectMentionSource(node, null, sb);
        return sb.toString();
    }

    private static void collectMentionSource(JsonNode node, String keyHint, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isTextual()) {
            String text = node.asText("");
            if (text.isBlank()) {
                return;
            }
            String key = keyHint == null ? "" : keyHint.toLowerCase(Locale.ROOT);
            boolean mentionKey = key.contains("atuser")
                    || key.contains("at_user")
                    || key.contains("mentioned")
                    || key.contains("msg_source")
                    || "remind".equals(key)
                    || "source".equals(key);
            if (mentionKey || text.contains("atuserlist") || text.contains("<msgsource")) {
                if (!out.isEmpty()) {
                    out.append('\n');
                }
                out.append(text);
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectMentionSource(child, keyHint, out);
            }
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                collectMentionSource(entry.getValue(), entry.getKey(), out);
            }
        }
    }

    private static String xmlAttr(String xml, String attr) {
        if (xml == null || xml.isBlank() || attr == null) {
            return "";
        }
        Matcher m = XML_ATTR.matcher(xml);
        while (m.find()) {
            if (attr.equalsIgnoreCase(m.group(1))) {
                return decodeXml(m.group(2));
            }
        }
        return "";
    }

    private static String matchGroup(Pattern pattern, String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String decodeXml(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .trim();
    }

    private static void putIfPresent(Map<String, Object> extra, String key, String value) {
        if (value != null && !value.isBlank()) {
            extra.put(key, value);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** http(s) → URL，否则 PLATFORM（微信 CDN id）。 */
    private static MediaRef mediaRefOfLocator(String locator) {
        if (looksLikeHttp(locator)) {
            return MediaRef.url(locator);
        }
        return MediaRef.platform(locator);
    }

    private static boolean looksLikeHttp(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String preview(String msg) {
        return preview(msg, 80);
    }

    private static String preview(String msg, int max) {
        if (msg == null) {
            return "";
        }
        String text = msg.replace('\n', ' ').trim();
        int limit = Math.max(16, max);
        return text.length() <= limit ? text : text.substring(0, limit) + "...";
    }

    private record GroupText(String senderId, String content) {
    }

    private record MappedContent(
            String msgType,
            String msg,
            String path,
            String replyToMsgId,
            Map<String, Object> extra
    ) {
    }
}
