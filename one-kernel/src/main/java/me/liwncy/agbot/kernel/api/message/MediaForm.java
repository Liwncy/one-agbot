package me.liwncy.agbot.kernel.api.message;

/**
 * 媒体传输形态：契约提供多种形式，由适配器按平台能力选用并填入 {@link MediaRef}。
 */
public enum MediaForm {
    /** HTTP(S) 可访问 URL（或其它 Agent/下游可直接拉取的 URL） */
    URL,
    /** 本地文件路径 */
    FILE,
    /** Base64 内容（配合 mime） */
    BASE64,
    /** 平台原生媒体标识（未解析；适配器可后续升级为 URL/FILE/BASE64） */
    PLATFORM;

    public static MediaForm parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MediaForm.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
