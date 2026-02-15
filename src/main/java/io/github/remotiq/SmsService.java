package io.github.remotiq;

import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * HTTP SMS fallback service.
 *
 * When the WhatsApp Cloud API is unreachable or returns an error, this service
 * sends the message content as a plain-text SMS via a configurable HTTP endpoint.
 *
 * The base URL should already contain any authentication tokens, API keys, or
 * other provider-specific parameters as URL query parameters.
 *
 * <h2>GET mode</h2>
 * Message parameters ({@code to}, {@code content}, {@code coding}) are appended
 * as URL query parameters:
 * <pre>
 *   https://sms-gateway.example.com/api/send?apiKey=abc123&amp;sender=MyApp&amp;to=%2B919876543210&amp;content=Your+code+is+123456&amp;coding=0
 * </pre>
 *
 * <h2>POST mode</h2>
 * The base URL (with auth params) stays unchanged. Message parameters are sent
 * in the request body as {@code application/x-www-form-urlencoded}. The
 * {@code _fallback_reason} debug parameter is only included in POST mode.
 * <pre>
 *   POST https://sms-gateway.example.com/api/send?apiKey=abc123&amp;sender=MyApp
 *   Content-Type: application/x-www-form-urlencoded
 *
 *   to=%2B919876543210&amp;content=Your+code+is+123456&amp;coding=0&amp;_fallback_reason=...
 * </pre>
 *
 * Data Coding Schemes (DCS) — widely accepted in the SMS industry:
 * <ul>
 *   <li>0  — GSM 7-bit default alphabet (Latin, 160 chars/segment)</li>
 *   <li>8  — UCS-2 / UTF-16 (for non-Latin scripts: Hindi, Arabic, etc., 70 chars/segment)</li>
 * </ul>
 *
 * The coding is always auto-detected from the message content — if it contains
 * non-ASCII characters, UCS-2 (8) is used; otherwise GSM 7-bit (0).
 */
public class SmsService {

    private static final Logger log = Logger.getLogger(SmsService.class);

    /** Shared HttpClient — thread-safe, reused across all SmsService instances. */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String baseUrl;
    private final String method; // "GET" or "POST"

    /**
     * @param baseUrl  HTTP(S) endpoint URL (may already contain query params with auth/API keys)
     * @param method   HTTP method to use: "GET" or "POST" (defaults to GET if null/invalid)
     */
    public SmsService(String baseUrl, String method) {
        this.baseUrl = baseUrl;
        this.method = (method != null && method.trim().equalsIgnoreCase("POST")) ? "POST" : "GET";
    }

    /**
     * Send an SMS via the fallback HTTP endpoint.
     *
     * Coding (DCS) is auto-detected from the message content:
     * - If the text contains non-ASCII characters (Hindi, Arabic, etc.) → 8 (UCS-2)
     * - Otherwise → 0 (GSM 7-bit)
     *
     * @param to      recipient phone in E.164 format
     * @param content plain-text message body (no HTML)
     * @return true if the endpoint returned 2xx
     */
    public boolean send(String to, String content) {
        return send(to, content, null);
    }

    /**
     * Send an SMS via the fallback HTTP endpoint with a fallback reason for debugging.
     *
     * @param to             recipient phone in E.164 format
     * @param content        plain-text message body (no HTML)
     * @param fallbackReason brief reason why WhatsApp failed (appended as _fallback_reason param)
     * @return true if the endpoint returned 2xx
     */
    public boolean send(String to, String content, String fallbackReason) {
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("SMS fallback URL not configured, skipping SMS");
            return false;
        }

        try {
            int coding = detectCoding(content);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30));

            if ("POST".equals(this.method)) {
                // POST: base URL unchanged, message params in request body
                builder.uri(URI.create(baseUrl))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                buildPostBody(to, content, coding, fallbackReason)));
            } else {
                // GET: all params appended to URL
                builder.uri(URI.create(buildGetUrl(to, content, coding)))
                        .GET();
            }

            HttpResponse<String> response = HTTP_CLIENT.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.infof("SMS sent to %s via fallback [%s] (coding=%d)", to, this.method, coding);
                return true;
            } else {
                log.errorf("SMS fallback error %d: %s", response.statusCode(), response.body());
                return false;
            }
        } catch (Exception e) {
            log.error("Failed to send SMS via fallback", e);
            return false;
        }
    }

    /**
     * Build the full GET URL by appending to, content, and coding as query parameters.
     * Handles base URLs that may or may not already have query parameters.
     */
    private String buildGetUrl(String to, String content, int coding) {
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator
                + "to=" + urlEncode(to)
                + "&content=" + urlEncode(content)
                + "&coding=" + coding;
    }

    /**
     * Build the POST request body as application/x-www-form-urlencoded.
     * Includes {@code _fallback_reason} for debugging why primary delivery failed.
     */
    private static String buildPostBody(String to, String content, int coding, String fallbackReason) {
        StringBuilder body = new StringBuilder();
        body.append("to=").append(urlEncode(to))
                .append("&content=").append(urlEncode(content))
                .append("&coding=").append(coding);
        if (fallbackReason != null && !fallbackReason.isBlank()) {
            body.append("&_fallback_reason=").append(urlEncode(fallbackReason));
        }
        return body.toString();
    }

    /**
     * Auto-detect the best DCS for the content.
     * If the message contains only GSM 7-bit compatible characters → 0
     * Otherwise → 8 (UCS-2 for Hindi, Arabic, etc.)
     */
    private static int detectCoding(String content) {
        if (content == null) return 0;
        for (char c : content.toCharArray()) {
            if (c > 0x7F) return 8; // UCS-2
        }
        return 0; // GSM 7-bit
    }

    private static String urlEncode(String value) {
        if (value == null) return "";
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
