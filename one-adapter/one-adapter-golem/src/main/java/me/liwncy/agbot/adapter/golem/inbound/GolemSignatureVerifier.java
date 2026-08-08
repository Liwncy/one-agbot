package me.liwncy.agbot.adapter.golem.inbound;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Golem Webhook 签名校验：HMAC-SHA256(token, timestamp + body)。
 */
public final class GolemSignatureVerifier {
    private GolemSignatureVerifier() {
    }

    public static boolean verify(String token, String signature, String timestamp, String body) {
        if (token == null || token.isBlank()) {
            return true;
        }
        if (signature == null || signature.isBlank() || timestamp == null || timestamp.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + (body == null ? "" : body)).getBytes(StandardCharsets.UTF_8));
            String expected = toHex(digest);
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
