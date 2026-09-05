package me.liwncy.agbot.agent;

import me.liwncy.agbot.agent.quickline.QuickLineSpec;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 把 Agent/模型侧技术错误收成口语短回复，避免把 {@code [ERROR] ...} 甩给微信用户。
 * 同类异常从候选里随机挑一句，避免每次同一句。
 */
final class AgentUserReply {

    private static final String[] BLOCKED = {
            "这句我接不住 😅",
            "有的话过不去，换个话题",
            "这轮看不了，聊点别的",
            "这话我不敢接 😅",
    };

    private static final String[] TIMEOUT = {
            "等太久了，待会再叫我",
            "卡住了，稍后再试",
            "超时了，过会儿再说",
    };

    private static final String[] STREAM_FAIL = {
            "刚才那轮没接住",
            "断了一下，待会再说",
            "这轮没整明白 🤔",
            "没回上来，过会儿再聊",
    };

    private static final String[] GENERIC = {
            "这边有点懵 😅",
            "没整好，待会再试",
            "出了点岔子",
            "这轮没搞定",
    };

    private static final String ERROR_REWRITE =
            "把原句改成更像你在微信里随口说的一句。只能一句，不超过20字。"
                    + "不要解释，不要提AI、审核、异常、模型。意思必须和原句同类。";

    private AgentUserReply() {
    }

    static QuickLineSpec errorRewrite(String seed, String speaker) {
        return new QuickLineSpec("error-rewrite", ERROR_REWRITE, seed, speaker);
    }

    static boolean isErrorish(String text) {
        return text == null || text.isBlank() || looksLikeError(text.trim());
    }

    /** 流结束但一个字都没有（451 被上游吞掉时也走这里）。 */
    static String fromEmptyStream() {
        return pick(STREAM_FAIL);
    }

    /** 同步对话返回的正文；识别服务端包进来的错误串。 */
    static String fromAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return fromEmptyStream();
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
