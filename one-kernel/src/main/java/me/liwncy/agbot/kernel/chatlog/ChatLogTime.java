package me.liwncy.agbot.kernel.chatlog;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把模型传来的日期/时间收成查询窗口。
 */
public final class ChatLogTime {

    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_HOURS = 168;

    private static final Pattern FULL = Pattern.compile(
            "(\\d{4})\\s*[-年/.]\\s*(\\d{1,2})\\s*[-月/.]\\s*(\\d{1,2})(?:\\s*日)?"
                    + "(?:\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?");
    private static final Pattern MONTH_DAY = Pattern.compile(
            "(\\d{1,2})\\s*[-月/.]\\s*(\\d{1,2})(?:\\s*日)?"
                    + "(?:\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?");
    private static final Pattern DAY_ONLY = Pattern.compile("^(\\d{1,2})\\s*号?$");

    private ChatLogTime() {
    }

    public record Window(LocalDateTime since, LocalDateTime until) {
        public String label() {
            if (since == null && until == null) {
                return "最近";
            }
            String start = since == null ? "最早" : LABEL.format(since);
            String end = until == null ? "现在" : LABEL.format(until);
            return start + " ~ " + end;
        }
    }

    public record Resolve(Window window, String error) {
        public static Resolve ok(Window window) {
            return new Resolve(window, null);
        }

        public static Resolve fail(String error) {
            return new Resolve(null, error);
        }

        public boolean failed() {
            return error != null && !error.isBlank();
        }
    }

    /**
     * 优先级：{@code date}（某天；可带时分秒）&gt; {@code from}/{@code until} &gt; {@code hours} &gt; 不限时间。
     */
    public static Resolve resolve(String date, String from, String until, Integer hours) {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        if (notBlank(date)) {
            Parsed parsed = parse(date.trim(), today, true);
            if (parsed == null) {
                return Resolve.fail("date 认不出来，请写成 2026-08-29，或 8-29、29号；也可带时间 2026-08-29 14:30:05。");
            }
            LocalDate day = parsed.dateTime().toLocalDate();
            LocalDateTime since = parsed.hasClock() ? parsed.dateTime() : day.atStartOfDay();
            return Resolve.ok(new Window(since, day.plusDays(1).atStartOfDay()));
        }
        if (notBlank(from) || notBlank(until)) {
            LocalDateTime since = null;
            LocalDateTime untilTime = null;
            if (notBlank(from)) {
                Parsed parsed = parse(from.trim(), today, true);
                if (parsed == null) {
                    return Resolve.fail("from 认不出来，请写成 2026-08-29 或 2026-08-29 17:00:00。");
                }
                since = parsed.hasClock() ? parsed.dateTime() : parsed.dateTime().toLocalDate().atStartOfDay();
            }
            if (notBlank(until)) {
                Parsed parsed = parse(until.trim(), today, true);
                if (parsed == null) {
                    return Resolve.fail("until 认不出来，请写成 2026-08-29 或 2026-08-29 18:00:00。");
                }
                untilTime = parsed.hasClock()
                        ? parsed.dateTime()
                        : parsed.dateTime().toLocalDate().plusDays(1).atStartOfDay();
            }
            if (since != null && untilTime != null && !since.isBefore(untilTime)) {
                return Resolve.fail("from 必须早于 until。");
            }
            return Resolve.ok(new Window(since, untilTime));
        }
        if (hours != null && hours >= 1) {
            int span = Math.min(hours, MAX_HOURS);
            return Resolve.ok(new Window(LocalDateTime.now().minusHours(span), null));
        }
        return Resolve.ok(new Window(null, null));
    }

    private static Parsed parse(String raw, LocalDate today, boolean allowClock) {
        String text = compact(raw);
        if (text.isEmpty()) {
            return null;
        }
        Matcher full = FULL.matcher(text);
        if (full.matches()) {
            return of(
                    parseInt(full.group(1)),
                    parseInt(full.group(2)),
                    parseInt(full.group(3)),
                    clock(full, 4, allowClock),
                    today,
                    false);
        }
        Matcher md = MONTH_DAY.matcher(text);
        if (md.matches()) {
            return of(
                    today.getYear(),
                    parseInt(md.group(1)),
                    parseInt(md.group(2)),
                    clock(md, 3, allowClock),
                    today,
                    true);
        }
        Matcher day = DAY_ONLY.matcher(text);
        if (day.matches()) {
            return ofDay(parseInt(day.group(1)), today);
        }
        return null;
    }

    private static Parsed of(int year, int month, int dayOfMonth, Clock clock, LocalDate today, boolean inferYear) {
        try {
            LocalDate date = LocalDate.of(year, month, dayOfMonth);
            if (inferYear && date.isAfter(today)) {
                date = date.minusYears(1);
            }
            LocalDateTime dateTime = clock == null
                    ? date.atStartOfDay()
                    : date.atTime(clock.hour(), clock.minute(), clock.second());
            return new Parsed(dateTime, clock != null);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static Parsed ofDay(int dayOfMonth, LocalDate today) {
        if (dayOfMonth < 1 || dayOfMonth > 31) {
            return null;
        }
        LocalDate candidate = clampDay(today.getYear(), today.getMonthValue(), dayOfMonth);
        if (candidate == null) {
            return null;
        }
        if (dayOfMonth > today.getDayOfMonth()) {
            LocalDate prev = today.minusMonths(1);
            candidate = clampDay(prev.getYear(), prev.getMonthValue(), dayOfMonth);
            if (candidate == null) {
                return null;
            }
        }
        return new Parsed(candidate.atStartOfDay(), false);
    }

    private static LocalDate clampDay(int year, int month, int dayOfMonth) {
        try {
            return LocalDate.of(year, month, dayOfMonth);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static Clock clock(Matcher matcher, int hourGroup, boolean allowClock) {
        if (!allowClock || matcher.group(hourGroup) == null) {
            return null;
        }
        int hour = parseInt(matcher.group(hourGroup));
        int minute = parseInt(matcher.group(hourGroup + 1));
        int second = matcher.group(hourGroup + 2) == null ? 0 : parseInt(matcher.group(hourGroup + 2));
        if (hour > 23 || minute > 59 || second > 59) {
            return null;
        }
        return new Clock(hour, minute, second);
    }

    private static String compact(String raw) {
        return raw.replace('号', ' ').replaceAll("\\s+", " ").trim();
    }

    private static int parseInt(String raw) {
        return Integer.parseInt(raw);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private record Parsed(LocalDateTime dateTime, boolean hasClock) {
    }

    private record Clock(int hour, int minute, int second) {
    }
}
