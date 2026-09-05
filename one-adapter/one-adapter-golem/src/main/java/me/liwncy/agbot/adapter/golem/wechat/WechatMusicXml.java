package me.liwncy.agbot.adapter.golem.wechat;

/**
 * 微信音乐应用消息 XML（appmsg type=3）。
 */
public final class WechatMusicXml {
    public static final int APP_TYPE = 3;

    private WechatMusicXml() {
    }

    public static String build(String title, String singer, String url, String dataUrl, String thumb) {
        return "<appmsg appid=\"" + WechatCardAppIds.randomAppId() + "\" sdkver=\"0\">"
                + "<title>" + escapeXml(firstNonBlank(title, "音乐")) + "</title>"
                + "<des>" + escapeXml(blankToEmpty(singer)) + "</des>"
                + "<action></action>"
                + "<type>" + APP_TYPE + "</type>"
                + "<showtype>0</showtype>"
                + "<url>" + escapeXml(blankToEmpty(url)) + "</url>"
                + "<lowurl></lowurl>"
                + "<dataurl>" + escapeXml(blankToEmpty(dataUrl)) + "</dataurl>"
                + "<lowdataurl></lowdataurl>"
                + "<songalbumurl>" + escapeXml(blankToEmpty(thumb)) + "</songalbumurl>"
                + "<songlyric></songlyric>"
                + "</appmsg>";
    }

    private static String escapeXml(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
