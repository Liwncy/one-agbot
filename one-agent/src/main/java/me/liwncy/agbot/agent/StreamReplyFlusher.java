package me.liwncy.agbot.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 缓冲流式文本，在段落空行 / 完整图片或视频 URL 边界切分后回调。
 * 不按句号切，避免微信里一条回复被拆成多条短消息。
 */
final class StreamReplyFlusher {
    private static final Pattern TRAILING_URL = Pattern.compile("https?://\\S+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEDIA_URL = Pattern.compile(
            "https?://[^\\s<>\"'\\]\\)\\u4e00-\\u9fff]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EMOJI_TOKEN = Pattern.compile(
            "(?i)emoji:[0-9a-f]{32}(?:[ \\t]+https?://\\S+)?");
    private static final Pattern CARD_LINE = Pattern.compile(
            "(?im)^(?:link:|music:|app:\\d+\\s).+$");

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
        int cardCut = cutAfterCompleteCardLine(text);
        if (cardCut > 0) {
            return cardCut;
        }
        if (hasIncompleteTrailingUrl(text) && !forceBoundary) {
            // 仅当中间已有可独立发出的完整媒体链时切开
            return cutAfterCompleteMediaUrl(text, false);
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

        int emojiCut = cutAfterCompleteEmojiToken(text);
        if (emojiCut > 0) {
            return emojiCut;
        }

        int mediaCut = cutAfterCompleteMediaUrl(text, true);
        if (mediaCut > 0) {
            return mediaCut;
        }

        // 不再按句号 / 单换行切分；剩余文本在 finish() 时整段发出。
        return -1;
    }

    private int cutAfterCompleteCardLine(String text) {
        Matcher m = CARD_LINE.matcher(text);
        int cut = -1;
        while (m.find()) {
            if (AgentOutboundCards.parseLine(m.group()) == null) {
                continue;
            }
            boolean followed = m.end() < text.length() && isUrlTerminator(text.charAt(m.end()));
            if (followed) {
                cut = m.end();
            }
        }
        return cut;
    }

    private int cutAfterCompleteEmojiToken(String text) {
        Matcher m = EMOJI_TOKEN.matcher(text);
        int cut = -1;
        while (m.find()) {
            boolean followed = m.end() < text.length() && isUrlTerminator(text.charAt(m.end()));
            if (followed || m.end() == text.length()) {
                cut = m.end();
            }
        }
        return cut;
    }

    private int cutAfterCompleteMediaUrl(String text, boolean allowAtEnd) {
        Matcher m = MEDIA_URL.matcher(text);
        int cut = -1;
        while (m.find()) {
            String url = m.group();
            boolean complete = AgentOutboundImages.looksCompleteImageUrl(url)
                    || AgentOutboundVideos.looksCompleteVideoUrl(url);
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
        if (AgentOutboundImages.looksLikeImageUrl(url)) {
            return !AgentOutboundImages.looksCompleteImageUrl(url);
        }
        if (AgentOutboundVideos.looksLikeVideoUrl(url)) {
            return !AgentOutboundVideos.looksCompleteVideoUrl(url);
        }
        // 未知 http(s) 链仍在流式拼接中
        return true;
    }

    private void emit(String part) {
        List<String> pieces = splitKeepMedia(part);
        for (String piece : pieces) {
            if (piece != null && !piece.isBlank()) {
                emitter.accept(piece);
            }
        }
    }

    /** 有图/视频/表情/卡片协议时尽量「配文 / 媒体」分开回调，便于通道分别发送。 */
    private static List<String> splitKeepMedia(String part) {
        AgentOutboundCards.Split cards = AgentOutboundCards.split(part);
        String afterCards = cards.hasCards() ? cards.remainingText() : part;
        AgentOutboundEmoji.Split emojis = AgentOutboundEmoji.split(afterCards);
        String afterEmoji = emojis.hasEmojis() ? emojis.remainingText() : afterCards;
        AgentOutboundImages.Split images = AgentOutboundImages.split(afterEmoji);
        AgentOutboundVideos.Split videos = AgentOutboundVideos.split(
                images.hasImages() ? images.remainingText() : afterEmoji);
        if (!cards.hasCards() && !emojis.hasEmojis() && !images.hasImages() && !videos.hasVideos()) {
            return List.of(part);
        }
        List<String> out = new ArrayList<>();
        String caption = videos.hasVideos()
                ? videos.remainingText()
                : (images.hasImages() ? images.remainingText() : afterEmoji);
        if (caption != null && !caption.isBlank()) {
            out.add(caption);
        }
        if (cards.hasCards()) {
            for (AgentOutboundCards.Ref ref : cards.cards()) {
                out.add(ref.toLine());
            }
        }
        if (emojis.hasEmojis()) {
            for (AgentOutboundEmoji.Ref ref : emojis.emojis()) {
                if (ref.imageUrl() == null || ref.imageUrl().isBlank()) {
                    out.add("emoji:" + ref.md5());
                } else {
                    out.add("emoji:" + ref.md5() + " " + ref.imageUrl());
                }
            }
        }
        if (images.hasImages()) {
            out.addAll(images.imageUrls());
        }
        if (videos.hasVideos()) {
            out.addAll(videos.videoUrls());
        }
        return out;
    }
}
