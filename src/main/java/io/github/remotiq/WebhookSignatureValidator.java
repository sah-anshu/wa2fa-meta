package io.github.remotiq;

import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Validates webhook payload signatures to prevent forged requests.
 *
 * <h2>Meta WhatsApp Cloud API (X-Hub-Signature-256)</h2>
 * Meta signs every webhook POST with HMAC-SHA256 using the App Secret.
 * The signature is sent in the {@code X-Hub-Signature-256} header as:
 * {@code sha256=<hex_signature>}
 *
 * @see <a href="https://developers.facebook.com/docs/graph-api/webhooks/getting-started#verification-requests">Meta Webhook Docs</a>
 */
public class WebhookSignatureValidator {

    private static final Logger log = Logger.getLogger(WebhookSignatureValidator.class);

    private WebhookSignatureValidator() {}

    /**
     * Validate Meta's X-Hub-Signature-256 header against the request body.
     *
     * @param body           raw request body
     * @param signatureHeader value of X-Hub-Signature-256 header (e.g. "sha256=abcdef1234...")
     * @param appSecret      Meta App Secret
     * @return true if signature is valid
     */
    public static boolean validateMetaSignature(String body, String signatureHeader, String appSecret) {
        if (appSecret == null || appSecret.isBlank()) {
            log.warn("WA2FA_APP_SECRET not configured — skipping webhook signature validation (INSECURE)");
            return true; // Skip validation if not configured (backward compatible)
        }

        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            log.warn("Missing or malformed X-Hub-Signature-256 header");
            return false;
        }

        String receivedSignature = signatureHeader.substring("sha256=".length());

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(
                    appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hmacBytes = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));

            String expectedSignature = bytesToHex(hmacBytes);

            // Constant-time comparison to prevent timing attacks
            boolean valid = MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.UTF_8),
                    receivedSignature.getBytes(StandardCharsets.UTF_8));

            if (!valid) {
                log.warn("X-Hub-Signature-256 verification failed — possible forged webhook request");
            }
            return valid;
        } catch (Exception e) {
            log.errorf("Error validating X-Hub-Signature-256: %s", e.getMessage());
            return false;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
