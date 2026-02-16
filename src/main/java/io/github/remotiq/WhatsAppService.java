/*
 * Copyright (c) 2026-present RemotiQ (Anshu S.)
 * SPDX-License-Identifier: MIT
 *
 * This file is part of wa2fa-meta (https://github.com/RemotiQ/wa2fa-meta).
 * Do not remove this header.
 * The copyright and permission notice must be included in all copies
 * or substantial portions of the Software. See LICENSE.
 */

package io.github.remotiq;

import org.jboss.logging.Logger;

import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class WhatsAppService {

    private static final Logger log = Logger.getLogger(WhatsAppService.class);

    /** Shared HttpClient — thread-safe, reused across all WhatsAppService instances.
     *  Avoids creating a new thread pool + SSL context per request. */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String accessToken;
    private final String phoneNumberId;
    private final String apiVersion;

    /** Last error message from a failed API call — useful for fallback debugging. */
    private volatile String lastError;

    public WhatsAppService(String accessToken, String phoneNumberId, String apiVersion) {
        this.accessToken = accessToken;
        this.phoneNumberId = phoneNumberId;
        this.apiVersion = apiVersion != null ? apiVersion : "v22.0";
    }

    /** Returns the last error message from a failed API call, or null if last call succeeded. */
    public String getLastError() {
        return lastError;
    }

    /**
     * Send a WhatsApp template message (without URL button).
     * Use this for utility/marketing templates like login_notification.
     *
     * @param recipientPhone phone in international format (e.g. +1234567890)
     * @param templateName   pre-approved template name in Meta Business
     * @param languageCode   language code (en, hi, es, fr, de, ar, pt)
     * @param parameters     body parameters for the template
     * @return true if the API returned a 2xx status
     */
    public boolean sendTemplateMessage(String recipientPhone,
                                       String templateName,
                                       String languageCode,
                                       String... parameters) {
        return sendTemplateMessageInternal(recipientPhone, templateName, languageCode, false, parameters);
    }

    /**
     * Send a WhatsApp authentication template message (with URL/copy-code button).
     * Meta authentication templates require the first body parameter (OTP code)
     * to also be passed as a URL button parameter at index 0.
     *
     * @param recipientPhone phone in international format (e.g. +1234567890)
     * @param templateName   pre-approved template name in Meta Business
     * @param languageCode   language code (en, hi, es, fr, de, ar, pt)
     * @param parameters     body parameters for the template (first param = OTP code)
     * @return true if the API returned a 2xx status
     */
    public boolean sendAuthTemplateMessage(String recipientPhone,
                                           String templateName,
                                           String languageCode,
                                           String... parameters) {
        return sendTemplateMessageInternal(recipientPhone, templateName, languageCode, true, parameters);
    }

    private boolean sendTemplateMessageInternal(String recipientPhone,
                                                String templateName,
                                                String languageCode,
                                                boolean includeButtonParam,
                                                String... parameters) {
        try {
            // Convert short lang code (en, pt) to Meta template locale (en_US, pt_BR)
            String metaLocale = LanguageResolver.toMetaLocale(languageCode);

            String endpoint = String.format(
                    "https://graph.facebook.com/%s/%s/messages",
                    apiVersion, phoneNumberId);

            String body = buildTemplateRequest(recipientPhone, templateName, metaLocale, includeButtonParam, parameters);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.infof("WhatsApp message sent to %s (template=%s, lang=%s)",
                        recipientPhone, templateName, languageCode);
                lastError = null;
                return true;
            } else {
                lastError = String.format("WA_API_%d: %s", response.statusCode(), extractErrorMessage(response.body()));
                log.errorf("WhatsApp API error %d: %s", response.statusCode(), response.body());
                return false;
            }
        } catch (Exception e) {
            lastError = "WA_EXCEPTION: " + e.getMessage();
            log.error("Failed to send WhatsApp message", e);
            return false;
        }
    }

    /**
     * Send a free-form text WhatsApp message (non-template).
     * Only works within the 24-hour conversation window (i.e., user messaged us first).
     *
     * @param recipientPhone phone in international format (e.g. +1234567890)
     * @param text           plain-text message body
     * @return true if the API returned a 2xx status
     */
    public boolean sendTextMessage(String recipientPhone, String text) {
        try {
            String endpoint = String.format(
                    "https://graph.facebook.com/%s/%s/messages",
                    apiVersion, phoneNumberId);

            String body = buildTextRequest(recipientPhone, text);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.infof("WhatsApp text message sent to %s", recipientPhone);
                lastError = null;
                return true;
            } else {
                lastError = String.format("WA_TEXT_%d: %s", response.statusCode(), extractErrorMessage(response.body()));
                log.errorf("WhatsApp text API error %d: %s", response.statusCode(), response.body());
                return false;
            }
        } catch (Exception e) {
            lastError = "WA_TEXT_EXCEPTION: " + e.getMessage();
            log.error("Failed to send WhatsApp text message", e);
            return false;
        }
    }

    private String buildTextRequest(String recipientPhone, String text) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"messaging_product\":\"whatsapp\",");
        sb.append("\"to\":\"").append(escapeJson(recipientPhone)).append("\",");
        sb.append("\"type\":\"text\",");
        sb.append("\"text\":{\"body\":\"").append(escapeJson(text)).append("\"}");
        sb.append("}");
        return sb.toString();
    }

    private String buildTemplateRequest(String recipientPhone,
                                        String templateName,
                                        String languageCode,
                                        boolean includeButtonParam,
                                        String... parameters) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"messaging_product\":\"whatsapp\",");
        sb.append("\"to\":\"").append(escapeJson(recipientPhone)).append("\",");
        sb.append("\"type\":\"template\",");
        sb.append("\"template\":{");
        sb.append("\"name\":\"").append(escapeJson(templateName)).append("\",");
        sb.append("\"language\":{\"policy\":\"deterministic\",\"code\":\"")
                .append(escapeJson(languageCode)).append("\"}");

        if (parameters != null && parameters.length > 0) {
            sb.append(",\"components\":[");

            // Body parameters
            sb.append("{\"type\":\"body\",\"parameters\":[");
            for (int i = 0; i < parameters.length; i++) {
                if (i > 0) sb.append(",");
                sb.append("{\"type\":\"text\",\"text\":\"")
                        .append(escapeJson(parameters[i])).append("\"}");
            }
            sb.append("]}");

            // URL button parameter: required for authentication templates with
            // copy-code / one-tap buttons. The first body parameter (OTP code)
            // is also passed as the button's URL suffix.
            if (includeButtonParam) {
                sb.append(",{\"type\":\"button\",\"sub_type\":\"url\",\"index\":\"0\",\"parameters\":[");
                sb.append("{\"type\":\"text\",\"text\":\"").append(escapeJson(parameters[0])).append("\"}");
                sb.append("]}");
            }

            sb.append("]");
        }

        sb.append("}}");
        return sb.toString();
    }

    /**
     * Extract a brief error message from Meta's JSON error response.
     * Input:  {"error":{"message":"(#131037) Display name not approved...","code":131037,...}}
     * Output: "(#131037) Display name not approved..."
     * Falls back to first 100 chars of raw body if parsing fails.
     */
    private static String extractErrorMessage(String responseBody) {
        if (responseBody == null) return "empty_response";
        try {
            int msgIdx = responseBody.indexOf("\"message\":\"");
            if (msgIdx >= 0) {
                int start = msgIdx + 11;
                int end = responseBody.indexOf("\"", start);
                if (end > start) {
                    String msg = responseBody.substring(start, Math.min(end, start + 120));
                    return msg;
                }
            }
        } catch (Exception ignored) {}
        return responseBody.substring(0, Math.min(responseBody.length(), 100));
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
