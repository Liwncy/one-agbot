package me.liwncy.agbot.agent.quickline;

/**
 * 短句生成请求。不带群上下文、不带 conversationId。
 *
 * @param task        调用方标识，只用于日志（如 {@code error-rewrite}）
 * @param instruction 这一次要模型干什么
 * @param seed        可选原句；失败时原样返回
 * @param speaker     口吻名字，如「小聪明儿」或当前角色名
 * @param maxChars    ≤0 则用配置默认
 */
public record QuickLineSpec(
        String task,
        String instruction,
        String seed,
        String speaker,
        int maxChars
) {
    public QuickLineSpec(String task, String instruction, String seed, String speaker) {
        this(task, instruction, seed, speaker, 0);
    }
}
