package me.liwncy.agbot.adapter.golem.mcp;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聊天记录卡脚本：对齐 xchatbot 文本项上限与时间格式，一次吃完角色和台词。
 */
final class FakeForwardScript {
    static final int MAX_ITEMS = 100;
    static final int MAX_ROLES = 10;
    static final int MAX_NAME_LENGTH = 30;
    static final int MAX_CONTENT_LENGTH = 300;
    static final ZoneId TZ = ZoneId.of("Asia/Shanghai");
    private static final Pattern HH_MM = Pattern.compile("^(\\d{1,2}):(\\d{2})$");
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private FakeForwardScript() {
    }

    record Line(String name, String content, long timestampMs) {
    }

    static List<Line> parse(String script) {
        return parse(script, LocalDateTime.now(TZ));
    }

    static List<Line> parse(String script, LocalDateTime now) {
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException("缺聊天内容。每行填 姓名|时间|内容，时间可空。");
        }
        List<Line> lines = new ArrayList<>();
        Long lastTs = null;
        String[] rawLines = script.split("\\R");
        for (String raw : rawLines) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            ParsedRow row = parseRow(trimmed);
            long ts;
            if (row.timeText() == null || row.timeText().isBlank()) {
                ts = lastTs == null ? toEpochMs(now) : lastTs + 60_000L;
            } else {
                ts = parseTime(row.timeText(), now.toLocalDate(), lastTs);
            }
            lastTs = ts;
            lines.add(new Line(row.name(), row.content(), ts));
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("缺聊天内容。每行填 姓名|时间|内容，时间可空。");
        }
        if (lines.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("一次最多 " + MAX_ITEMS + " 条聊天。");
        }
        Set<String> roles = new LinkedHashSet<>();
        for (Line line : lines) {
            roles.add(line.name());
        }
        if (roles.size() > MAX_ROLES) {
            throw new IllegalArgumentException("一次最多 " + MAX_ROLES + " 个角色。");
        }
        return List.copyOf(lines);
    }

    private record ParsedRow(String name, String timeText, String content) {
    }

    private static ParsedRow parseRow(String line) {
        String[] parts = line.split("\\|", 3);
        if (parts.length == 1) {
            throw new IllegalArgumentException("行格式应为 姓名|时间|内容 或 姓名|内容：" + clip(line));
        }
        String name = normalizeName(parts[0]);
        if (parts.length == 2) {
            return new ParsedRow(name, "", normalizeContent(parts[1]));
        }
        String middle = parts[1].trim();
        String content = parts[2];
        if (middle.isEmpty() || looksLikeTime(middle)) {
            return new ParsedRow(name, middle, normalizeContent(content));
        }
        return new ParsedRow(name, "", normalizeContent(middle + "|" + content));
    }

    private static boolean looksLikeTime(String text) {
        return HH_MM.matcher(text).matches() || text.matches("^\\d{4}-\\d{2}-\\d{2}\\s+\\d{1,2}:\\d{2}$");
    }

    static long parseTime(String input, LocalDate today, Long referenceTs) {
        String trimmed = input.trim();
        Matcher shortTime = HH_MM.matcher(trimmed);
        if (shortTime.matches()) {
            int hour = Integer.parseInt(shortTime.group(1));
            int minute = Integer.parseInt(shortTime.group(2));
            LocalDate date = today;
            if (referenceTs != null) {
                date = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(referenceTs), TZ).toLocalDate();
            }
            try {
                return toEpochMs(LocalDateTime.of(date, LocalTime.of(hour, minute)));
            } catch (DateTimeException e) {
                throw new IllegalArgumentException("时间格式应为 HH:mm 或 YYYY-MM-DD HH:mm：" + trimmed);
            }
        }
        try {
            LocalDateTime full = LocalDateTime.parse(normalizeFull(trimmed), FULL);
            return toEpochMs(full);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("时间格式应为 HH:mm 或 YYYY-MM-DD HH:mm：" + trimmed);
        }
    }

    private static String normalizeFull(String trimmed) {
        Matcher m = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{1,2}):(\\d{2})$").matcher(trimmed);
        if (!m.matches()) {
            return trimmed;
        }
        return m.group(1) + " " + pad2(Integer.parseInt(m.group(2))) + ":" + m.group(3);
    }

    private static String normalizeName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("角色姓名不能为空");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("角色姓名不能超过 " + MAX_NAME_LENGTH + " 个字");
        }
        return name;
    }

    private static String normalizeContent(String value) {
        String content = value == null ? "" : value.trim();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("聊天内容不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("单条内容不能超过 " + MAX_CONTENT_LENGTH + " 个字");
        }
        return content;
    }

    private static long toEpochMs(LocalDateTime time) {
        return time.atZone(TZ).toInstant().toEpochMilli();
    }

    private static String pad2(int value) {
        return String.format("%02d", value);
    }

    private static String clip(String text) {
        return text.length() <= 40 ? text : text.substring(0, 40) + "...";
    }
}
