package me.liwncy.agbot.agent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 把 Agent/模型侧技术错误收成口语短回复，避免把 {@code [ERROR] ...} 甩给微信用户。
 * 同类异常从候选里随机挑一句，避免每次同一句。
 */
final class AgentUserReply {

    private static final String[] BLOCKED = {
            "这内容我看不了 😅",
            "这张过不去，换一张呗",
            "这个我接不住 😅",
            "审核不让看，再换个试试",
    };

    private static final String[] TIMEOUT = {
            "等太久了，再发一次",
            "卡住了，稍后再试下",
            "超时了，再来一句？",
    };

    private static final String[] STREAM_FAIL = {
            "没回上来，再试下",
            "刚才没接住，再说一遍？",
            "断了一下，再发一次",
            "没整明白，你再发下 🤔",
    };

    private static final String[] GENERIC = {
            "没整好，再试下",
            "出了点岔子，再来一次",
            "这边有点懵，稍后再试 😅",
            "没搞定，你再发一遍？",
    };

    private static final String[] EMPTY = {
            "没回上来，再试下",
            "空空的，再说一句？",
            "没收到有效回复，再发下",
    };

    private AgentUserReply() {
    }

    /** 同步对话返回的正文；识别服务端包进来的错误串。 */
    static String fromAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return pick(EMPTY);
        }
        String text = answer.trim();
        if (!looksLikeError(text)) {
            return answer;
        }
        return friendly(text, null);
    }

    /** 调用异常（含 OpenAPI / 网络等）。 */
    static String fromThrowable(Throwable error) {
        String detail = flatten(error);
        return friendly(detail, error);
    }

    private static boolean looksLikeError(String text) {
        String lower = text.toLowerCase();
        return text.startsWith("[ERROR]")
                || lower.contains("stream processing failed")
                || lower.contains("aggregation error")
                || lower.contains("unexpectedstatuscodeexception")
                || (lower.contains("451") && lower.contains("block"))
                || lower.contains("content you provided or machine outputted is blocked")
                || lower.contains("content is blocked");
    }

    private static String friendly(String detail, Throwable error) {
        String lower = (detail == null ? "" : detail).toLowerCase();
        if (error != null) {
            lower = (lower + " " + flatten(error)).toLowerCase();
        }
        if (lower.contains("451")
                || lower.contains("blocked")
                || lower.contains("content is blocked")
                || lower.contains("machine outputted is blocked")) {
            return pick(BLOCKED);
        }
        if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("超时")) {
            return pick(TIMEOUT);
        }
        if (lower.contains("stream processing failed")
                || lower.contains("aggregation error")
                || detail != null && detail.startsWith("[ERROR]")) {
            return pick(STREAM_FAIL);
        }
        return pick(GENERIC);
    }

    private static String pick(String[] options) {
        return options[ThreadLocalRandom.current().nextInt(options.length)];
    }

    private static String flatten(Throwable error) {
        if (error == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Throwable cur = error;
        int guard = 0;
        while (cur != null && guard++ < 8) {
            if (cur.getMessage() != null) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(cur.getMessage());
            }
            cur = cur.getCause();
        }
        return sb.toString();
    }
}
