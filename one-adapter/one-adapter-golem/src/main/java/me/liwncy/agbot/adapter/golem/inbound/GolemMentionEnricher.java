package me.liwncy.agbot.adapter.golem.inbound;

import me.liwncy.agbot.adapter.golem.api.GolemChatroomRoster;
import me.liwncy.agbot.kernel.api.message.ChannelExtraKeys;
import me.liwncy.agbot.kernel.api.message.MsgInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 群 @ 的 wxid 配上群里显示的名字，名字取自群花名册。
 * <p>不输出头像：头像由 {@code golem_fake_forward} 按名字自己去花名册取，
 * 免得上下文里摆着一堆 URL 被安到别人头上。</p>
 */
public class GolemMentionEnricher {
    private static final Logger log = LoggerFactory.getLogger(GolemMentionEnricher.class);

    private final GolemChatroomRoster roster;

    public GolemMentionEnricher(GolemChatroomRoster roster) {
        this.roster = roster;
    }

    public MsgInfo enrich(MsgInfo msg) {
        if (msg == null || msg.isPrivateChat()) {
            return msg;
        }
        Map<String, Object> extra = msg.extra() == null ? Map.of() : msg.extra();
        List<String> ids = mentionIds(extra.get(ChannelExtraKeys.MENTION_IDS));
        if (ids.isEmpty()) {
            return msg;
        }
        List<Map<String, String>> mentions = new ArrayList<>();
        int named = 0;
        int seq = 1;
        for (String id : ids) {
            GolemChatroomRoster.Member member = roster.findById(msg.groupId(), id);
            String name = member == null ? "" : member.name();
            if (!name.isBlank()) {
                named++;
            }
            Map<String, String> row = new LinkedHashMap<>();
            row.put("seq", String.valueOf(seq++));
            row.put("id", id);
            row.put("name", name);
            mentions.add(row);
        }
        Map<String, Object> next = new HashMap<>(extra);
        next.put(ChannelExtraKeys.MENTIONS, mentions);
        log.info("Golem mention enrich groupId={} count={} named={} ids={}",
                msg.groupId(), mentions.size(), named, ids);
        return new MsgInfo(
                msg.platform(),
                msg.accountId(),
                msg.userId(),
                msg.userName(),
                msg.groupId(),
                msg.groupName(),
                msg.msg(),
                msg.msgId(),
                msg.fromType(),
                msg.msgType(),
                msg.path(),
                msg.replyToMsgId(),
                msg.createTime(),
                Map.copyOf(next)
        );
    }

    static List<String> mentionIds(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> ids = new ArrayList<>();
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                String id = String.valueOf(item).trim();
                if (!id.isEmpty() && !"notify@all".equalsIgnoreCase(id)) {
                    ids.add(id);
                }
            }
            return ids;
        }
        if (raw instanceof String text && !text.isBlank()) {
            List<String> ids = new ArrayList<>();
            for (String part : text.split("[,;，；\\s]+")) {
                String id = part.trim();
                if (!id.isEmpty() && !"notify@all".equalsIgnoreCase(id)) {
                    ids.add(id);
                }
            }
            return ids;
        }
        return List.of();
    }
}
