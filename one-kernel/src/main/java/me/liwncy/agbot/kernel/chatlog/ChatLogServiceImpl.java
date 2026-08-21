package me.liwncy.agbot.kernel.chatlog;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import java.util.Collections;
import java.util.List;

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
        row.setAdapterExtra(ChatLogExtras.toJson(msgInfo.extra()));
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
        int limit = query.limit() < 1 ? 20 : Math.min(query.limit(), 50);
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
        wrapper.orderByDesc(ChatMessage::getId);
        wrapper.last("LIMIT " + limit);
        List<ChatMessage> rows = mapper.selectList(wrapper);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> chronological = new ArrayList<>(rows);
        Collections.reverse(chronological);
        return chronological;
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

    @Override
    public List<ChatMessage> listReplyContext(MsgInfo current, int maxMinutes, int maxRows) {
        if (current == null || current.isPrivateChat()) {
            return List.of();
        }
        int minutes = maxMinutes < 1 ? 30 : Math.min(maxMinutes, 180);
        int limit = maxRows < 1 ? 20 : Math.min(maxRows, 40);
        String accountId = blankTo(current.accountId(), "default");
        String sessionId = ChatLogSessions.of(current);
        String platform = ChatLogChannels.channelPlatform(current.platform());
        LocalDateTime since = LocalDateTime.now().minusMinutes(minutes);
        Long afterId = findLastOutboundId(accountId, sessionId, platform, since);

        LambdaQueryWrapper<ChatMessage> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(ChatMessage::getAccountId, accountId);
        wrapper.eq(ChatMessage::getSessionId, sessionId);
        wrapper.eq(ChatMessage::getPlatform, platform);
        wrapper.ge(ChatMessage::getMsgTime, since);
        if (afterId != null) {
            wrapper.gt(ChatMessage::getId, afterId);
        }
        if (current.msgId() != null && !current.msgId().isBlank()) {
            wrapper.ne(ChatMessage::getMessageId, current.msgId().trim());
        }
        wrapper.orderByDesc(ChatMessage::getId);
        wrapper.last("LIMIT " + limit);
        List<ChatMessage> rows = mapper.selectList(wrapper);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> chronological = new ArrayList<>(rows);
        Collections.reverse(chronological);
        return chronological;
    }

    private Long findLastOutboundId(String accountId, String sessionId, String platform, LocalDateTime since) {
        LambdaQueryWrapper<ChatMessage> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(ChatMessage::getAccountId, accountId);
        wrapper.eq(ChatMessage::getSessionId, sessionId);
        wrapper.eq(ChatMessage::getPlatform, platform);
        wrapper.eq(ChatMessage::getDirection, DIRECTION_OUTBOUND);
        wrapper.ge(ChatMessage::getMsgTime, since);
        wrapper.orderByDesc(ChatMessage::getId);
        wrapper.last("LIMIT 1");
        ChatMessage last = mapper.selectOne(wrapper);
        return last == null ? null : last.getId();
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
