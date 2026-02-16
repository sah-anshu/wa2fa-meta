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

import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory store for QR code verification tokens.
 *
 * When a user chooses the "Scan QR Code" method, a unique token is generated
 * and stored here. When the user scans the QR code and sends a WhatsApp message
 * to our business number, the webhook receives the message, matches the token,
 * and marks it as verified.
 *
 * The browser polls a status endpoint to detect when verification is complete.
 *
 * Entries expire after a configurable TTL (default 5 minutes) and are cleaned
 * up lazily during lookups.
 */
public class VerificationStore {

    private static final Logger log = Logger.getLogger(VerificationStore.class);

    // Singleton instance — shared across all Keycloak sessions
    private static final VerificationStore INSTANCE = new VerificationStore();

    public static VerificationStore getInstance() {
        return INSTANCE;
    }

    /**
     * Represents a pending QR verification.
     */
    public static class PendingVerification {
        private final String token;          // unique code in the QR (e.g. "WA2FA-A3B7C9")
        private final String expectedPhone;  // E.164 phone that must send the message
        private final long createdAt;        // epoch seconds
        private final int ttlSeconds;        // how long before this entry expires
        private volatile boolean verified;            // set to true when webhook receives the message
        private volatile boolean pendingPhoneConfirm; // token matched but phone ownership not yet confirmed (LID)
        private volatile String senderPhone;          // actual phone that sent the message

        public PendingVerification(String token, String expectedPhone, int ttlSeconds) {
            this.token = token;
            this.expectedPhone = expectedPhone;
            this.createdAt = Instant.now().getEpochSecond();
            this.ttlSeconds = ttlSeconds;
            this.verified = false;
            this.pendingPhoneConfirm = false;
        }

        public String getToken() { return token; }
        public String getExpectedPhone() { return expectedPhone; }
        public long getCreatedAt() { return createdAt; }
        public boolean isVerified() { return verified; }
        public boolean isPendingPhoneConfirm() { return pendingPhoneConfirm; }
        public String getSenderPhone() { return senderPhone; }

        public int getTtlSeconds() { return ttlSeconds; }

        public boolean isExpired() {
            return Instant.now().getEpochSecond() - createdAt > ttlSeconds;
        }

        public void markVerified(String senderPhone) {
            this.verified = true;
            this.pendingPhoneConfirm = false;
            this.senderPhone = senderPhone;
        }

        /**
         * Mark as pending phone confirmation (LID first-time mapping).
         * Token matched, but phone ownership needs to be confirmed via verification link.
         */
        public void markPendingPhoneConfirm() {
            this.pendingPhoneConfirm = true;
        }

        /**
         * Confirm phone ownership after user clicks verification link.
         * Transitions from pending_phone_confirm → verified.
         */
        public void confirmPhoneOwnership(String confirmedPhone) {
            this.verified = true;
            this.pendingPhoneConfirm = false;
            this.senderPhone = confirmedPhone;
        }
    }

    /**
     * Maximum number of pending verifications before forced cleanup.
     * Prevents unbounded memory growth under sustained load.
     */
    private static final int MAX_PENDING_SIZE = 10_000;

    /** Minimum interval between proactive cleanups in seconds (avoid thrashing) */
    private static final long CLEANUP_INTERVAL_SECONDS = 60;

    // token → PendingVerification
    private final Map<String, PendingVerification> pendingMap = new ConcurrentHashMap<>();

    // sessionId → token (maps auth session to its active QR token, for polling)
    private final Map<String, String> sessionTokenMap = new ConcurrentHashMap<>();

    /** Last cleanup epoch seconds */
    private final AtomicLong lastCleanupTime = new AtomicLong(0);

    private VerificationStore() {}

    /**
     * Create a new pending verification for a QR code scan.
     *
     * @param token         the verification code embedded in the QR code
     * @param expectedPhone the phone number (E.164) that must send the message
     * @param ttlSeconds    how long before expiry
     * @param sessionId     the auth session ID (for polling)
     */
    public void createPending(String token, String expectedPhone, int ttlSeconds, String sessionId) {
        // Proactive cleanup: run if map exceeds threshold or cleanup interval elapsed
        if (pendingMap.size() > MAX_PENDING_SIZE / 2 || shouldCleanup()) {
            cleanupExpired();
        }

        // Hard cap: reject new entries if still over max after cleanup
        if (pendingMap.size() >= MAX_PENDING_SIZE) {
            log.warnf("VerificationStore at capacity (%d entries). Forcing full cleanup.", pendingMap.size());
            cleanupExpired();
            if (pendingMap.size() >= MAX_PENDING_SIZE) {
                log.errorf("VerificationStore still full after cleanup. Evicting oldest entry.");
                evictOldest();
            }
        }

        // Remove any previous token for this session
        String oldToken = sessionTokenMap.get(sessionId);
        if (oldToken != null) {
            pendingMap.remove(oldToken);
        }

        PendingVerification pv = new PendingVerification(token, expectedPhone, ttlSeconds);
        pendingMap.put(token, pv);
        sessionTokenMap.put(sessionId, token);
        log.debugf("Created QR verification: token=%s, phone=%s, session=%s (store size: %d)",
                token, expectedPhone, sessionId, pendingMap.size());
    }

    private boolean shouldCleanup() {
        long now = Instant.now().getEpochSecond();
        long last = lastCleanupTime.get();
        return (now - last) > CLEANUP_INTERVAL_SECONDS;
    }

    /** Evict the oldest entry (by creation time) as a last resort. */
    private void evictOldest() {
        PendingVerification oldest = null;
        String oldestToken = null;
        for (Map.Entry<String, PendingVerification> entry : pendingMap.entrySet()) {
            if (oldest == null || entry.getValue().getCreatedAt() < oldest.getCreatedAt()) {
                oldest = entry.getValue();
                oldestToken = entry.getKey();
            }
        }
        if (oldestToken != null) {
            pendingMap.remove(oldestToken);
            sessionTokenMap.values().remove(oldestToken);
        }
    }

    /**
     * Result of processing an incoming WhatsApp message for QR verification.
     */
    public static class VerificationResult {
        public enum Status { MATCHED, NO_MATCH, EXPIRED, PHONE_MISMATCH }

        private final Status status;
        private final String expectedPhoneLast4; // last 4 digits of expected phone (for hint in failure msg)

        public VerificationResult(Status status, String expectedPhoneLast4) {
            this.status = status;
            this.expectedPhoneLast4 = expectedPhoneLast4;
        }

        public Status getStatus() { return status; }
        public boolean isMatched() { return status == Status.MATCHED; }
        public String getExpectedPhoneLast4() { return expectedPhoneLast4; }

        private static String last4(String phone) {
            if (phone == null || phone.length() < 4) return "****";
            return phone.substring(phone.length() - 4);
        }
    }

    /**
     * Called by the webhook when an incoming WhatsApp message is received.
     * Tries to match the message body against a pending token AND verify the sender phone.
     *
     * @param senderPhone    the phone that sent the message (E.164 from WhatsApp, without +)
     * @param messageBody    the text body of the incoming message
     * @return VerificationResult with status and optional phone hint for failure messages
     */
    public VerificationResult handleIncomingMessage(String senderPhone, String messageBody) {
        return handleIncomingMessage(senderPhone, messageBody, false);
    }

    /**
     * Called by the webhook when an incoming WhatsApp message is received.
     * Tries to match the message body against a pending token AND verify the sender phone.
     *
     * When senderIsLid is true, the phone check is skipped because a Linked ID (LID)
     * was received instead of a real phone number.
     *
     * @param senderPhone    the phone that sent the message (E.164 from WhatsApp, without +)
     * @param messageBody    the text body of the incoming message
     * @param senderIsLid    true if senderPhone is a LID (not a real phone number)
     * @return VerificationResult with status and optional phone hint for failure messages
     */
    public VerificationResult handleIncomingMessage(String senderPhone, String messageBody, boolean senderIsLid) {
        if (messageBody == null || messageBody.isBlank())
            return new VerificationResult(VerificationResult.Status.NO_MATCH, null);

        // Extract the token from the message body (trimmed, uppercased).
        // Token format is "{REALM}-XXXXXXXXX" (e.g. "WEBSMSC-A3B7C9D2E").
        String body = messageBody.trim().toUpperCase();

        // Try direct token match
        PendingVerification pv = pendingMap.get(body);
        if (pv == null) {
            // Case-insensitive search across all pending tokens
            for (PendingVerification candidate : pendingMap.values()) {
                if (candidate.getToken().equalsIgnoreCase(body)) {
                    pv = candidate;
                    break;
                }
            }
        }

        if (pv == null) {
            log.debugf("No pending QR verification found for message: %s", messageBody);
            return new VerificationResult(VerificationResult.Status.NO_MATCH, null);
        }

        // Already verified — ignore duplicate webhook events
        if (pv.isVerified()) {
            log.debugf("QR token already verified, ignoring duplicate: %s", pv.getToken());
            return new VerificationResult(VerificationResult.Status.NO_MATCH, null);
        }

        if (pv.isExpired()) {
            log.debugf("QR verification expired for token: %s", pv.getToken());
            pendingMap.remove(pv.getToken());
            return new VerificationResult(VerificationResult.Status.EXPIRED,
                    VerificationResult.last4(pv.getExpectedPhone()));
        }

        // When sender is a LID (Linked ID), skip phone matching.
        // The token match alone is sufficient proof — only the QR scanner could know it.
        // NOTE: We do NOT mark as verified here. The webhook handler decides whether to:
        //   - Verify immediately (if LID→phone mapping is already cached), or
        //   - Send a phone ownership verification link (first-time LID)
        if (senderIsLid) {
            // Already pending phone confirmation — ignore duplicate webhook
            if (pv.isPendingPhoneConfirm()) {
                log.debugf("QR token already pending phone confirm, ignoring duplicate: %s", pv.getToken());
                return new VerificationResult(VerificationResult.Status.NO_MATCH, null);
            }
            log.infof("QR token matched (LID sender, verification deferred to webhook): token=%s, expectedPhone=%s, lid=%s",
                    pv.getToken(), pv.getExpectedPhone(), senderPhone);
            return new VerificationResult(VerificationResult.Status.MATCHED, null);
        }

        // Normalize sender phone for comparison
        // WhatsApp sends phone without '+', e.g. "919876543210"
        // Our stored phone is E.164 with '+', e.g. "+919876543210"
        String normalizedSender = senderPhone.startsWith("+") ? senderPhone : "+" + senderPhone;
        String normalizedExpected = pv.getExpectedPhone();

        if (!normalizedSender.equals(normalizedExpected)) {
            log.warnf("QR verification phone mismatch: expected=%s, got=%s, token=%s",
                    normalizedExpected, normalizedSender, pv.getToken());
            return new VerificationResult(VerificationResult.Status.PHONE_MISMATCH,
                    VerificationResult.last4(normalizedExpected));
        }

        pv.markVerified(normalizedSender);
        log.infof("QR verification successful: token=%s, phone=%s", pv.getToken(), normalizedSender);
        return new VerificationResult(VerificationResult.Status.MATCHED, null);
    }

    /**
     * Check verification status for a given auth session.
     *
     * @param sessionId the auth session ID
     * @return the PendingVerification if found (may be verified or expired), or null
     */
    public PendingVerification getStatus(String sessionId) {
        String token = sessionTokenMap.get(sessionId);
        if (token == null) return null;

        PendingVerification pv = pendingMap.get(token);
        if (pv == null) return null;

        // Clean up expired entries (but keep pending_phone_confirm alive until link is clicked or TTL)
        if (pv.isExpired() && !pv.isVerified() && !pv.isPendingPhoneConfirm()) {
            pendingMap.remove(token);
            sessionTokenMap.remove(sessionId);
            return null;
        }
        return pv;
    }

    /**
     * Check verification status by token directly.
     */
    public PendingVerification getByToken(String token) {
        if (token == null) return null;
        PendingVerification pv = pendingMap.get(token);
        if (pv != null && pv.isExpired() && !pv.isVerified() && !pv.isPendingPhoneConfirm()) {
            pendingMap.remove(token);
            return null;
        }
        return pv;
    }

    /**
     * Clean up a completed or expired verification.
     */
    public void remove(String sessionId) {
        String token = sessionTokenMap.remove(sessionId);
        if (token != null) {
            pendingMap.remove(token);
        }
    }

    /**
     * Cleanup expired entries. Also removes verified entries older than 2x TTL
     * (they should have been consumed by the polling endpoint by then).
     */
    public void cleanupExpired() {
        lastCleanupTime.set(Instant.now().getEpochSecond());
        int removed = 0;

        Iterator<Map.Entry<String, PendingVerification>> it = pendingMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, PendingVerification> entry = it.next();
            PendingVerification pv = entry.getValue();

            boolean shouldRemove = false;

            // Remove expired unverified entries
            if (pv.isExpired() && !pv.isVerified()) {
                shouldRemove = true;
            }

            // Remove verified entries that are past 2x TTL (stale — browser already consumed)
            if (pv.isVerified()) {
                long age = Instant.now().getEpochSecond() - pv.getCreatedAt();
                if (age > pv.getTtlSeconds() * 2L) {
                    shouldRemove = true;
                }
            }

            if (shouldRemove) {
                it.remove();
                sessionTokenMap.values().remove(entry.getKey());
                removed++;
            }
        }

        if (removed > 0) {
            log.debugf("VerificationStore cleanup: removed %d entries, remaining: %d", removed, pendingMap.size());
        }
    }

    /**
     * Get the current store sizes (for monitoring/logging).
     */
    public int size() {
        return pendingMap.size();
    }
}
