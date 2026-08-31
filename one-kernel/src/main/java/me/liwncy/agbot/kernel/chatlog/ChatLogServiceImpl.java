package me.liwncy.agbot.kernel.chatlog;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import me.liwncy.agbot.kernel.api.message.ChannelExtraKeys;
import me.liwncy.agbot.kernel.api.message.MsgInfo;
import me.liwncy.agbot.kernel.api.message.MsgType;
import me.liwncy.agbot.kernel.api.message.ReplyInfo;
import me.liwncy.agbot.kernel.chatlog.domain.ChatMessage;
import me.liwncy.agbot.kernel.chatlog.mapper.ChatMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChatLogServiceImpl implements ChatLogService {
    private static final Logger log = LoggerFactory.getLogger(ChatLogServiceImpl.class);

    public static final String DIRECTION_INBOUND = "inbound";
    public static final String DIRECTION_OUTBOUND = "outbound";
    public static final String SESSION_GROUP = "group";
    public static final String SESSION_PRIVATE = "private";

    private final ChatMessageMapper mapper;

    public ChatLogServiceImpl(ChatMessageMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void recordInbound(MsgInfo msgInfo) {
        if (msgInfo == null) {
            return;
        }
        ChatMessage row = new ChatMessage();
        String adapterId = blankTo(msgInfo.platform(), "unknown");
        row.setPlatform(ChatLogChannels.channelPlatform(adapterId));
        row.setAdapter(adapterId);
        row.setAccountId(blankTo(msgInfo.accountId(), "default"));
        row.setMessageId(blankTo(msgInfo.msgId(), "in-" + System.nanoTime()));
        applySession(row, msgInfo.userId(), msgInfo.groupId());
        row.setDirection(DIRECTION_INBOUND);
        row.setSenderId(blankTo(msgInfo.userId(), "unknown"));
        row.setSenderName(nullToEmpty(msgInfo.userName()));
        row.setMsgType(MsgType.normalize(msgInfo.msgType()));
        row.setContentText(ChatLogExtras.clip(firstNonBlank(msgInfo.msg(), msgInfo.path()), ChatLogExtras.MAX_CONTENT));
        row.setAdapterExtra(ChatLogExtras.toJson(extraWithPath(msgInfo)));
        row.setReferMessageId(emptyToNull(msgInfo.replyToMsgId()));
        row.setReplyIndex(0);
        row.setMsgTime(toMsgTime(msgInfo.createTime()));
        insertQuietly(row);
    }

    @Override
    public void recordOutbound(String adapterId, ReplyInfo replyInfo, String outboundMsgId, String replyStatus) {
        if (replyInfo == null) {
            return;
        }
        String adapter = blankTo(adapterId, blankTo(replyInfo.accountId(), "unknown"));
        ChatMessage row = new ChatMessage();
        row.setPlatform(ChatLogChannels.channelPlatform(adapter));
        row.setAdapter(adapter);
        row.setAccountId(blankTo(replyInfo.accountId(), "default"));
        String causedBy = emptyToNull(replyInfo.toMsgId());
        row.setMessageId(blankTo(outboundMsgId, "out-" + blankTo(causedBy, "na") + "-" + System.nanoTime()));
        applySession(row, replyInfo.userId(), replyInfo.groupId());
        row.setDirection(DIRECTION_OUTBOUND);
        row.setSenderId(blankTo(replyInfo.accountId(), "bot"));
        row.setSenderName("bot");
        row.setMsgType(MsgType.normalize(replyInfo.type()));
        row.setContentText(ChatLogExtras.clip(
                firstNonBlank(replyInfo.msg(), replyInfo.title(), replyInfo.path(), replyInfo.url()),
                ChatLogExtras.MAX_CONTENT));
        row.setAdapterExtra(ChatLogExtras.toJson(replyInfo.extra()));
        row.setCausedByMessageId(causedBy);
        row.setReplyIndex(0);
        row.setReplyStatus(emptyToNull(replyStatus));
        row.setMsgTime(LocalDateTime.now());
        insertQuietly(row);
    }

    @Override
    public List<ChatMessage> listRecent(ChatLogQuery query) {
        if (query == null || query.sessionId() == null || query.sessionId().isBlank()) {
            return List.of();
        }
        int limit = query.limit() < 1 ? 20 : Math.min(query.limit(), 200);
        LambdaQueryWrapper<ChatMessage> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(ChatMessage::getAccountId, blankTo(query.accountId(), "default"));
        wrapper.eq(ChatMessage::getSessionId, query.sessionId().trim());
        if (query.platform() != null && !query.platform().isBlank()) {
            wrapper.eq(ChatMessage::getPlatform, ChatLogChannels.channelPlatform(query.platform().trim()));
        }
        if (query.direction() != null && !query.direction().isBlank()) {
            wrapper.eq(ChatMessage::getDirection, query.direction().trim());
        }
        if (query.since() != null) {
            wrapper.ge(ChatMessage::getMsgTime, query.since());
        }
        if (query.until() != null) {
            wrapper.lt(ChatMessage::getMsgTime, query.until());
        }
        applySpeaker(wrapper, query);
        String keyword = sanitizeLike(query.keyword());
        if (!keyword.isEmpty()) {
            wrapper.like(ChatMessage::getContentText, keyword);
        }
        if (query.msgType() != null && !query.msgType().isBlank()) {
            wrapper.eq(ChatMessage::getMsgType, query.msgType().trim());
        }
        boolean pageOlder = query.beforeId() != null && query.beforeId() > 0;
        boolean pageNewer = !pageOlder && query.afterId() != null && query.afterId() > 0;
        if (pageOlder) {
            wrapper.lt(ChatMessage::getId, query.beforeId());
        } else if (pageNewer) {
            wrapper.gt(ChatMessage::getId, query.afterId());
        }
        // 有时间窗且不是往回翻：从窗口开头往后拿，方便总结某天
        boolean chronologicalAsc = pageNewer || (query.since() != null && !pageOlder);
        if (chronologicalAsc) {
            wrapper.orderByAsc(ChatMessage::getId);
        } else {
            wrapper.orderByDesc(ChatMessage::getId);
        }
        wrapper.last("LIMIT " + limit);
        List<ChatMessage> rows = mapper.selectList(wrapper);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        if (chronologicalAsc) {
            return rows;
        }
        List<ChatMessage> chronological = new ArrayList<>(rows);
        Collections.reverse(chronological);
        return chronological;
    }

    @Override
    public List<ChatMessage> listRecentContext(String accountId, String sessionId,
                                               LocalDateTime since, String excludeMessageId, int limit) {
        if (sessionId == null || sessionId.isBlank() || since == null || limit < 1) {
            return List.of();
        }
        int size = Math.min(limit, 50);
        LambdaQueryWrapper<ChatMessage> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(ChatMessage::getAccountId, blankTo(accountId, "default"));
        wrapper.eq(ChatMessage::getSessionId, sessionId.trim());
        wrapper.eq(ChatMessage::getDirection, DIRECTION_INBOUND);
        wrapper.ge(ChatMessage::getMsgTime, since);
        String exclude = emptyToNull(excludeMessageId);
        if (exclude != null) {
            wrapper.ne(ChatMessage::getMessageId, exclude);
        }
        wrapper.orderByDesc(ChatMessage::getId);
        wrapper.last("LIMIT " + size);
        List<ChatMessage> rows = mapper.selectList(wrapper);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> chronological = new ArrayList<>(rows);
        Collections.reverse(chronological);
        return chronological;
    }

    @Override
    public Set<String> listRepliedMessageIds(String accountId, String sessionId,
                                             Collection<String> inboundMessageIds) {
        if (sessionId == null || sessionId.isBlank() || inboundMessageIds == null || inboundMessageIds.isEmpty()) {
            return Set.of();
        }
        List<String> ids = new ArrayList<>();
        for (String id : inboundMessageIds) {
            String trimmed = emptyToNull(id);
            if (trimmed != null && !ids.contains(trimmed)) {
                ids.add(trimmed);
            }
            if (ids.size() >= 50) {
                break;
            }
        }
        if (ids.isEmpty()) {
            return Set.of();
        }
        LambdaQueryWrapper<ChatMessage> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(ChatMessage::getAccountId, blankTo(accountId, "default"));
        wrapper.eq(ChatMessage::getSessionId, sessionId.trim());
        wrapper.eq(ChatMessage::getDirection, DIRECTION_OUTBOUND);
        wrapper.in(ChatMessage::getCausedByMessageId, ids);
        wrapper.select(ChatMessage::getCausedByMessageId);
        List<ChatMessage> rows = mapper.selectList(wrapper);
        if (rows == null || rows.isEmpty()) {
            return Set.of();
        }
        Set<String> replied = new LinkedHashSet<>();
        for (ChatMessage row : rows) {
            String caused = emptyToNull(row.getCausedByMessageId());
            if (caused != null) {
                replied.add(caused);
            }
        }
        return replied;
    }

    @Override
    public List<ChatMessage> listByMessageId(String accountId, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return List.of();
        }
        LambdaQueryWrapper<ChatMessage> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(ChatMessage::getAccountId, blankTo(accountId, "default"));
        wrapper.eq(ChatMessage::getMessageId, messageId.trim());
        wrapper.orderByAsc(ChatMessage::getId);
        List<ChatMessage> rows = mapper.selectList(wrapper);
        return rows == null ? List.of() : rows;
    }

    private void insertQuietly(ChatMessage row) {
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException e) {
            log.debug("Chat log duplicate platform={} accountId={} messageId={}",
                    row.getPlatform(), row.getAccountId(), row.getMessageId());
        } catch (Exception e) {
            log.warn("Chat log insert failed platform={} adapter={} messageId={}: {}",
                    row.getPlatform(), row.getAdapter(), row.getMessageId(), e.getMessage());
        }
    }

    private static void applySpeaker(LambdaQueryWrapper<ChatMessage> wrapper, ChatLogQuery query) {
        String senderId = emptyToNull(query.senderId());
        String nameLike = sanitizeLike(query.senderName());
        if (senderId != null && !nameLike.isEmpty()) {
            wrapper.and(w -> w.eq(ChatMessage::getSenderId, senderId)
                    .or()
                    .like(ChatMessage::getSenderName, nameLike));
            return;
        }
        if (senderId != null) {
            wrapper.eq(ChatMessage::getSenderId, senderId);
            return;
        }
        if (!nameLike.isEmpty()) {
            wrapper.like(ChatMessage::getSenderName, nameLike);
        }
    }

    private static String sanitizeLike(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.trim().replace("\\", "").replace("%", "").replace("_", "");
    }

    /** FILE 的 path 不在 extra 里；入库补一份，避免配文盖掉路径后历史图找不回。 */
    private static Map<String, Object> extraWithPath(MsgInfo msgInfo) {
        Map<String, Object> extra = msgInfo.extra() == null
                ? new HashMap<>()
                : new HashMap<>(msgInfo.extra());
        String path = emptyToNull(msgInfo.path());
        if (path != null && extra.get(ChannelExtraKeys.MEDIA_URL) == null) {
            extra.put(ChannelExtraKeys.MEDIA_URL, path);
        }
        return extra;
    }

    private static void applySession(ChatMessage row, String userId, String groupId) {
        boolean group = groupId != null && !groupId.isBlank() && !"0".equals(groupId);
        row.setSessionType(group ? SESSION_GROUP : SESSION_PRIVATE);
        row.setSessionId(ChatLogSessions.of(userId, groupId));
    }

    private static LocalDateTime toMsgTime(Long epochMs) {
        long ms = epochMs == null || epochMs <= 0 ? System.currentTimeMillis() : epochMs;
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault());
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String emptyToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
