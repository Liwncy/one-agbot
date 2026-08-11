package me.liwncy.agbot.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 缓冲流式文本，在段落 / 完整图片 URL / 句号边界切分后回调。
 */
final class StreamReplyFlusher {
    private static final Pattern TRAILING_URL = Pattern.compile("https?://\\S+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_URL = Pattern.compile(
            "https?://[^\\s<>\"'\\]\\)\\u4e00-\\u9fff]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？!?\\n]");

    private final StringBuilder buffer = new StringBuilder();
    private final int minChars;
    private final Consumer<String> emitter;

    StreamReplyFlusher(int minChars, Consumer<String> emitter) {
        this.minChars = Math.max(1, minChars);
        this.emitter = emitter;
    }

    void append(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        buffer.append(delta);
        flushReady(false);
    }

    void finish() {
        flushReady(true);
        if (!buffer.isEmpty()) {
            emit(buffer.toString());
            buffer.setLength(0);
        }
    }

    private void flushReady(boolean forceBoundary) {
        while (true) {
            String current = buffer.toString();
            if (current.isBlank()) {
                return;
            }
            int cut = findCut(current, forceBoundary);
            if (cut <= 0) {
                return;
            }
            String part = current.substring(0, cut).trim();
            buffer.delete(0, cut);
            // 吃掉分段后多余空行
            while (!buffer.isEmpty() && (buffer.charAt(0) == '\n' || buffer.charAt(0) == '\r')) {
                buffer.deleteCharAt(0);
            }
            if (!part.isBlank()) {
                emit(part);
            }
        }
    }

    private int findCut(String text, boolean forceBoundary) {
        if (hasIncompleteTrailingUrl(text) && !forceBoundary) {
            // 仅当中间已有可独立发出的完整图链时切开
            int imageCut = cutAfterCompleteImageUrl(text, false);
            return imageCut;
        }

        int para = text.indexOf("\n\n");
        if (para >= 0) {
            int end = para;
            while (end < text.length() && text.charAt(end) == '\n') {
                end++;
            }
            String head = text.substring(0, para);
            if (!hasIncompleteTrailingUrl(head) && head.trim().length() >= Math.min(minChars, 1)) {
                return Math.max(para + 2, end);
            }
        }

        int imageCut = cutAfterCompleteImageUrl(text, true);
        if (imageCut > 0) {
            return imageCut;
        }

        if (text.trim().length() < minChars && !forceBoundary) {
            return -1;
        }

        Matcher sentence = SENTENCE_END.matcher(text);
        int last = -1;
        while (sentence.find()) {
            int end = sentence.end();
            String head = text.substring(0, end);
            if (hasIncompleteTrailingUrl(head)) {
                continue;
            }
            if (head.trim().length() >= minChars) {
                last = end;
            }
        }
        return last;
    }

    private int cutAfterCompleteImageUrl(String text, boolean allowAtEnd) {
        Matcher m = IMAGE_URL.matcher(text);
        int cut = -1;
        while (m.find()) {
            String url = m.group();
            boolean complete = AgentOutboundImages.looksCompleteImageUrl(url);
            boolean followed = m.end() < text.length() && isUrlTerminator(text.charAt(m.end()));
            if (complete && (followed || (allowAtEnd && m.end() == text.length()))) {
                cut = m.end();
            }
        }
        return cut;
    }

    private static boolean isUrlTerminator(char c) {
        return Character.isWhitespace(c) || c == ')' || c == ']' || c == '"' || c == '\''
                || c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == ',' || c == '，';
    }

    private static boolean hasIncompleteTrailingUrl(String text) {
        Matcher m = TRAILING_URL.matcher(text.trim());
        if (!m.find()) {
            return false;
        }
        String url = m.group();
        return !AgentOutboundImages.looksCompleteImageUrl(url);
    }

    private void emit(String part) {
        List<String> pieces = splitKeepImages(part);
        for (String piece : pieces) {
            if (piece != null && !piece.isBlank()) {
                emitter.accept(piece);
            }
        }
    }

    /** 有图时尽量「配文 / 图」分开回调，便于通道分别发送。 */
    private static List<String> splitKeepImages(String part) {
        AgentOutboundImages.Split split = AgentOutboundImages.split(part);
        if (!split.hasImages()) {
            return List.of(part);
        }
        List<String> out = new ArrayList<>();
        if (split.remainingText() != null && !split.remainingText().isBlank()) {
            out.add(split.remainingText());
        }
        out.addAll(split.imageUrls());
        return out;
    }
}
