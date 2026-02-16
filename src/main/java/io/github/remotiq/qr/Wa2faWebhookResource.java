/*
 * Copyright (c) 2026-present RemotiQ (Anshu S.)
 * SPDX-License-Identifier: MIT
 *
 * This file is part of wa2fa-meta (https://github.com/RemotiQ/wa2fa-meta).
 * Do not remove this header.
 * The copyright and permission notice must be included in all copies
 * or substantial portions of the Software. See LICENSE.
 */

package io.github.remotiq.qr;

import io.github.remotiq.Wa2faConfig;
import io.github.remotiq.Wa2faExecutor;
import io.github.remotiq.WebhookSignatureValidator;
import io.github.remotiq.WhatsAppService;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;

/**
 * JAX-RS REST resource providing:
 *
 * 1. GET  /realms/{realm}/wa2fa/webhook  — WhatsApp webhook verification (hub.challenge)
 * 2. POST /realms/{realm}/wa2fa/webhook  — Receives incoming WhatsApp messages
 * 3. GET  /realms/{realm}/wa2fa/qr-status?token={token}  — Polls QR verification status
 *
 * The webhook must be configured in Meta Business Manager as:
 *   https://your-keycloak.com/realms/{realm}/wa2fa/webhook
 *
 * WhatsApp Cloud API sends a GET request with hub.mode, hub.challenge, hub.verify_token
 * for initial verification, and POST for incoming messages.
 */
public class Wa2faWebhookResource {

    private static final Logger log = Logger.getLogger(Wa2faWebhookResource.class);

    private final KeycloakSession session;
    private final String webhookVerifyToken;
    private final String appSecret;

    public Wa2faWebhookResource(KeycloakSession session, String webhookVerifyToken, String appSecret) {
        this.session = session;
        this.webhookVerifyToken = webhookVerifyToken;
        this.appSecret = appSecret;
    }

    /**
     * WhatsApp Webhook Verification (GET).
     * Meta sends: GET ?hub.mode=subscribe&amp;hub.challenge=xxx&amp;hub.verify_token=yyy
     * We must return hub.challenge if hub.verify_token matches our configured token.
     */
    @GET
    @Path("webhook")
    @Produces(MediaType.TEXT_PLAIN)
    public Response verifyWebhook(
            @QueryParam("hub.mode") String mode,
            @QueryParam("hub.challenge") String challenge,
            @QueryParam("hub.verify_token") String verifyToken) {

        if ("subscribe".equals(mode) && challenge != null) {
            if (webhookVerifyToken != null && webhookVerifyToken.equals(verifyToken)) {
                log.info("WhatsApp webhook verified successfully");
                return Response.ok(challenge).build();
            }
            log.warn("WhatsApp webhook verification failed: token mismatch");
            return Response.status(Response.Status.FORBIDDEN).entity("Verify token mismatch").build();
        }

        return Response.ok("wa2fa webhook active").build();
    }

    /**
     * WhatsApp Incoming Message (POST).
     * Meta sends a JSON payload with the incoming message details.
     *
     * Example payload structure:
     * {
     *   "entry": [{
     *     "changes": [{
     *       "value": {
     *         "messages": [{
     *           "from": "919876543210",
     *           "text": { "body": "VERIFY-A3B7C9D2E" }
     *         }]
     *       }
     *     }]
     *   }]
     * }
     */
    @POST
    @Path("webhook")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response receiveMessage(@Context HttpHeaders headers, String body) {
        log.debugf("WhatsApp webhook POST received: %s", body);

        // Validate X-Hub-Signature-256 from Meta to prevent forged webhook requests
        String signature = headers != null ? headers.getHeaderString("X-Hub-Signature-256") : null;
        if (!WebhookSignatureValidator.validateMetaSignature(body, signature, appSecret)) {
            log.warn("Rejecting webhook POST: invalid X-Hub-Signature-256");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\":\"invalid_signature\"}").build();
        }

        try {
            // Parse the incoming message manually (no JSON library dependency)
            String senderPhone = extractJsonValue(body, "from");
            String messageBody = extractNestedJsonValue(body, "text", "body");

            if (senderPhone != null && messageBody != null) {
                log.infof("Incoming WhatsApp message from %s: %s", senderPhone, messageBody);

                VerificationStore.VerificationResult result =
                        VerificationStore.getInstance().handleIncomingMessage(senderPhone, messageBody);

                if (result.isMatched()) {
                    log.infof("QR verification matched for phone %s", senderPhone);
                } else {
                    log.debugf("QR verification status %s for message from %s",
                            result.getStatus(), senderPhone);
                }

                // Send acknowledgement reply asynchronously
                sendAckReplyAsync(senderPhone, result);
            }
        } catch (Exception e) {
            log.error("Error processing WhatsApp webhook", e);
        }

        // Always return 200 to WhatsApp (they retry on non-200)
        return Response.ok("{\"status\":\"received\"}").build();
    }

    /**
     * Send an acknowledgement WhatsApp reply based on verification result.
     * Uses free-form text within the 24h conversation window (user just messaged us by scanning QR).
     * No template needed — the conversation window is already open.
     * Runs asynchronously so the webhook returns 200 immediately.
     */
    private void sendAckReplyAsync(String senderPhone, VerificationStore.VerificationResult result) {
        // Resolve config to build a WhatsAppService for sending replies
        Wa2faConfig cfg;
        try {
            cfg = Wa2faConfig.resolve(session, session.getContext().getRealm());
        } catch (Exception e) {
            log.debugf("Could not resolve wa2fa config for ack reply: %s", e.getMessage());
            return;
        }

        String accessToken = cfg.accessToken();
        String phoneNumberId = cfg.phoneNumberId();
        if (accessToken == null || accessToken.isBlank() || phoneNumberId == null || phoneNumberId.isBlank()) {
            log.debug("Skipping QR ack reply — no access token or phone number ID configured");
            return;
        }

        String replyText;
        switch (result.getStatus()) {
            case MATCHED:
                replyText = cfg.qrAckVerified();
                break;
            case PHONE_MISMATCH:
                replyText = cfg.qrAckMismatch()
                        .replace("{{last4}}", result.getExpectedPhoneLast4() != null ? result.getExpectedPhoneLast4() : "****");
                break;
            case EXPIRED:
                replyText = cfg.qrAckExpired();
                break;
            case NO_MATCH:
            default:
                replyText = cfg.qrAckNoMatch();
                break;
        }

        final String reply = replyText;
        final WhatsAppService waService = new WhatsAppService(accessToken, phoneNumberId, cfg.apiVersion());

        // Normalize phone: add + if missing for E.164
        final String phone = senderPhone.startsWith("+") ? senderPhone : "+" + senderPhone;

        Wa2faExecutor.submit(() -> {
            try {
                boolean sent = waService.sendTextMessage(phone, reply);
                if (sent) {
                    log.infof("Sent QR ack reply (%s) to %s via Meta Cloud API", result.getStatus(), phone);
                } else {
                    log.warnf("Failed to send QR ack reply to %s via Meta Cloud API", phone);
                }
            } catch (Exception e) {
                log.errorf("Error sending QR ack reply to %s: %s", phone, e.getMessage());
            }
        });
    }

    /**
     * Poll QR verification status (GET).
     * Called by the browser via JavaScript to check if the user has completed
     * the QR code verification flow.
     *
     * Returns JSON: {"status": "pending|verified|expired|not_found"}
     */
    @GET
    @Path("qr-status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response checkQrStatus(@QueryParam("token") String token) {
        if (token == null || token.isBlank()) {
            return Response.ok("{\"status\":\"not_found\"}").build();
        }

        VerificationStore.PendingVerification pv = VerificationStore.getInstance().getByToken(token);

        if (pv == null) {
            return Response.ok("{\"status\":\"not_found\"}").build();
        }

        if (pv.isVerified()) {
            return Response.ok("{\"status\":\"verified\",\"phone\":\"" + escapeJson(pv.getSenderPhone()) + "\"}").build();
        }

        if (pv.isExpired()) {
            return Response.ok("{\"status\":\"expired\"}").build();
        }

        return Response.ok("{\"status\":\"pending\"}").build();
    }

    // --- Simple JSON parsing helpers (no library dependency) ---

    /**
     * Extract a simple string value from JSON by key.
     * Works for flat values like "from": "919876543210"
     */
    private String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx == -1) return null;

        // Find the colon after the key
        int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx == -1) return null;

        // Find the opening quote of the value
        int openQuote = json.indexOf('"', colonIdx + 1);
        if (openQuote == -1) return null;

        // Find the closing quote
        int closeQuote = json.indexOf('"', openQuote + 1);
        if (closeQuote == -1) return null;

        return json.substring(openQuote + 1, closeQuote);
    }

    /**
     * Extract a nested string value like "text": { "body": "VERIFY-A3B7C9D2E" }
     */
    private String extractNestedJsonValue(String json, String parentKey, String childKey) {
        String searchKey = "\"" + parentKey + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx == -1) return null;

        // Find the opening brace of the parent object
        int braceIdx = json.indexOf('{', keyIdx + searchKey.length());
        if (braceIdx == -1) return null;

        // Search within the parent object for the child key
        String childSearch = "\"" + childKey + "\"";
        int childIdx = json.indexOf(childSearch, braceIdx);
        if (childIdx == -1) return null;

        // Find the colon after the child key
        int colonIdx = json.indexOf(':', childIdx + childSearch.length());
        if (colonIdx == -1) return null;

        // Find the opening quote of the value
        int openQuote = json.indexOf('"', colonIdx + 1);
        if (openQuote == -1) return null;

        // Find the closing quote (handle escaped quotes)
        int closeQuote = findClosingQuote(json, openQuote + 1);
        if (closeQuote == -1) return null;

        return json.substring(openQuote + 1, closeQuote);
    }

    private int findClosingQuote(String json, int startIdx) {
        for (int i = startIdx; i < json.length(); i++) {
            if (json.charAt(i) == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                return i;
            }
        }
        return -1;
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
