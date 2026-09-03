package me.liwncy.agbot.adapter.golem.wechat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 微信「聊天记录」应用消息 XML（type=19），对齐 xchatbot {@code buildWechatChatRecordAppXml} 文本项。
 */
public final class WechatChatRecordXml {
    public static final int APP_TYPE = 19;
    private static final ZoneId TZ = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter SOURCE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String DEFAULT_TITLE = "群聊的聊天记录";

    private WechatChatRecordXml() {
    }

    public record Item(String nickname, String content, String avatarUrl, long timestampMs) {
        public Item {
            nickname = nickname == null ? "" : nickname.trim();
            content = content == null ? "" : content;
            avatarUrl = avatarUrl == null ? "" : avatarUrl.trim();
            if (timestampMs <= 0) {
                timestampMs = System.currentTimeMillis();
            }
        }
    }

    public static String build(String title, String summary, String desc, List<Item> items) {
        List<Item> kept = new ArrayList<>();
        if (items != null) {
            for (Item item : items) {
                if (item != null && !item.nickname().isBlank() && item.content() != null && !item.content().isBlank()) {
                    kept.add(item);
                }
            }
        }
        if (kept.isEmpty()) {
            throw new IllegalArgumentException("chat record needs at least one text item");
        }
        String summaryText = firstNonBlank(summary, defaultSummary(kept));
        String descText = firstNonBlank(desc, summaryText);
        String titleText = firstNonBlank(title, DEFAULT_TITLE);
        long favCreateTimeSeconds = kept.get(kept.size() - 1).timestampMs() / 1000;
        StringBuilder dataItems = new StringBuilder();
        for (int i = 0; i < kept.size(); i++) {
            if (i > 0) {
                dataItems.append('\n');
            }
            dataItems.append(dataItem(kept.get(i), i));
        }
        String recordInfo = "<recordinfo>\n"
                + "<fromscene>0</fromscene>\n"
                + "<favcreatetime>" + favCreateTimeSeconds + "</favcreatetime>\n"
                + "<isChatRoom>0</isChatRoom>\n"
                + "<title>" + escapeXml(titleText) + "</title>\n"
                + "<desc>" + escapeXml(descText) + "</desc>\n"
                + "<datalist count=\"" + kept.size() + "\">\n"
                + dataItems + "\n"
                + "</datalist>\n"
                + "</recordinfo>";
        String xml = "<appmsg appid=\"\" sdkver=\"0\">\n"
                + "<title>" + escapeXml(titleText) + "</title>\n"
                + "<des>" + escapeXml(summaryText) + "</des>\n"
                + "<action/>\n"
                + "<type>" + APP_TYPE + "</type>\n"
                + "<showtype>0</showtype>\n"
                + "<soundtype>0</soundtype>\n"
                + "<mediatagname/>\n"
                + "<messageext/>\n"
                + "<messageaction/>\n"
                + "<content/>\n"
                + "<contentattr>0</contentattr>\n"
                + "<url/>\n"
                + "<lowurl/>\n"
                + "<dataurl/>\n"
                + "<lowdataurl/>\n"
                + "<songalbumurl/>\n"
                + "<songlyric/>\n"
                + "<template_id/>\n"
                + "<appattach>\n"
                + "<totallen>0</totallen>\n"
                + "<attachid/>\n"
                + "<emoticonmd5></emoticonmd5>\n"
                + "<fileext/>\n"
                + "<aeskey></aeskey>\n"
                + "</appattach>\n"
                + "<extinfo/>\n"
                + "<sourceusername/>\n"
                + "<sourcedisplayname/>\n"
                + "<thumburl/>\n"
                + "<md5/>\n"
                + "<statextstr/>\n"
                + "<recorditem><![CDATA[" + recordInfo + "]]></recorditem>\n"
                + "</appmsg>";
        return minify(xml);
    }

    public static String emitLine(String xml) {
        return "app:" + APP_TYPE + " " + xml;
    }

    private static String dataItem(Item item, int index) {
        long timestampMs = item.timestampMs();
        String sourceMsgId = String.valueOf(timestampMs);
        String localId = String.valueOf(index + 1);
        String nickname = escapeXml(item.nickname());
        String content = escapeXml(item.content());
        String avatarUrl = escapeXml(item.avatarUrl());
        String sourceTime = SOURCE_TIME.format(Instant.ofEpochMilli(timestampMs).atZone(TZ));
        String dataSeed = nickname + "|" + content + "|" + sourceTime + "|" + sourceMsgId + "|" + localId;
        String dataId = escapeXml(pseudoHex(dataSeed, 32));
        String hashUsername = escapeXml(pseudoHex(nickname + "|" + avatarUrl + "|" + sourceMsgId, 64));
        return "<dataitem datatype=\"1\" dataid=\"" + dataId + "\" htmlid=\"" + dataId + "\">\n"
                + "<sourcename>" + nickname + "</sourcename>\n"
                + "<sourceheadurl>" + avatarUrl + "</sourceheadurl>\n"
                + "<sourcetime>" + sourceTime + "</sourcetime>\n"
                + "<datadesc>" + content + "</datadesc>\n"
                + "<srcMsgLocalid>" + localId + "</srcMsgLocalid>\n"
                + "<srcMsgCreateTime>" + (timestampMs / 1000) + "</srcMsgCreateTime>\n"
                + "<fromnewmsgid>" + sourceMsgId + "</fromnewmsgid>\n"
                + "<dataitemsource>\n"
                + "<hashusername>" + hashUsername + "</hashusername>\n"
                + "</dataitemsource>\n"
                + "</dataitem>";
    }

    private static String defaultSummary(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(4, items.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            Item item = items.get(i);
            sb.append(item.nickname()).append(": ").append(item.content().trim());
        }
        return sb.toString();
    }

    static String minify(String xml) {
        return xml.replaceAll(">\\s+<", "><").trim();
    }

    static String escapeXml(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
                .replace("\r", "")
                .replace("\n", "&#10;");
    }

    static String pseudoHex(String input, int length) {
        int h1 = 0x811c9dc5;
        int h2 = 0x9e3779b9;
        for (int i = 0; i < input.length(); i++) {
            int code = input.charAt(i);
            h1 = (h1 ^ code) * 0x01000193;
            h2 = (h2 ^ (code + i + 1)) * 0x85ebca6b;
        }
        StringBuilder output = new StringBuilder();
        while (output.length() < length) {
            h1 = (h1 ^ (h2 >>> 16)) * 0xc2b2ae35;
            h2 = (h2 ^ (h1 >>> 13)) * 0x27d4eb2f;
            output.append(pad8(Integer.toUnsignedString(h1, 16)));
            output.append(pad8(Integer.toUnsignedString(h2, 16)));
        }
        return output.substring(0, length);
    }

    private static String pad8(String hex) {
        if (hex.length() >= 8) {
            return hex;
        }
        return "0".repeat(8 - hex.length()) + hex;
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
