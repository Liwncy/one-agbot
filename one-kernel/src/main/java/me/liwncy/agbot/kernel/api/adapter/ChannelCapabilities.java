package me.liwncy.agbot.kernel.api.adapter;

import me.liwncy.agbot.kernel.api.message.MsgType;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 适配器对通道契约上限的实际支持声明。
 */
public final class ChannelCapabilities {
    private final Set<String> inboundTypes;
    private final Set<String> outboundTypes;
    private final boolean revoke;
    private final boolean quote;
    private final boolean remind;

    private ChannelCapabilities(Builder builder) {
        this.inboundTypes = Collections.unmodifiableSet(new LinkedHashSet<>(builder.inboundTypes));
        this.outboundTypes = Collections.unmodifiableSet(new LinkedHashSet<>(builder.outboundTypes));
        this.revoke = builder.revoke;
        this.quote = builder.quote;
        this.remind = builder.remind;
    }

    public Set<String> inboundTypes() {
        return inboundTypes;
    }

    public Set<String> outboundTypes() {
        return outboundTypes;
    }

    public boolean revoke() {
        return revoke;
    }

    public boolean quote() {
        return quote;
    }

    public boolean remind() {
        return remind;
    }

    public boolean supportsOutbound(String type) {
        return outboundTypes.contains(MsgType.normalize(type));
    }

    public boolean supportsInbound(String type) {
        return inboundTypes.contains(MsgType.normalize(type));
    }

    @Override
    public String toString() {
        return "ChannelCapabilities{in=" + inboundTypes
                + ", out=" + outboundTypes
                + ", revoke=" + revoke
                + ", quote=" + quote
                + ", remind=" + remind + "}";
    }

    /** 契约上限：全部类型 + 撤回/引用/@。 */
    public static ChannelCapabilities all() {
        return builder()
                .inboundTypes(MsgType.ALL)
                .outboundTypes(MsgType.ALL)
                .revoke(true)
                .quote(true)
                .remind(true)
                .build();
    }

    /** 仅文本（最弱）。 */
    public static ChannelCapabilities textOnly() {
        return builder()
                .inboundTypes(Set.of(MsgType.TEXT))
                .outboundTypes(Set.of(MsgType.TEXT))
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Set<String> inboundTypes = Set.of(MsgType.TEXT);
        private Set<String> outboundTypes = Set.of(MsgType.TEXT);
        private boolean revoke;
        private boolean quote;
        private boolean remind;

        public Builder inboundTypes(Set<String> types) {
            this.inboundTypes = normalizeSet(types);
            return this;
        }

        public Builder outboundTypes(Set<String> types) {
            this.outboundTypes = normalizeSet(types);
            return this;
        }

        public Builder revoke(boolean revoke) {
            this.revoke = revoke;
            return this;
        }

        public Builder quote(boolean quote) {
            this.quote = quote;
            return this;
        }

        public Builder remind(boolean remind) {
            this.remind = remind;
            return this;
        }

        public ChannelCapabilities build() {
            return new ChannelCapabilities(this);
        }

        private static Set<String> normalizeSet(Set<String> types) {
            Objects.requireNonNull(types, "types");
            Set<String> out = new LinkedHashSet<>();
            for (String type : types) {
                out.add(MsgType.normalize(type));
            }
            return out;
        }
    }
}
