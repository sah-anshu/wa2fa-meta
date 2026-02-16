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

import java.security.SecureRandom;

/**
 * Generates unique, short, human-readable verification tokens for QR code flow.
 *
 * Token format: "{REALM}-XXXXXXXXX" where {REALM} is the uppercased realm name
 * (non-alphanumeric characters stripped) and X is alphanumeric (uppercase, 9 chars).
 * Example: "WEBSMSC-A3B7C9D2E" for realm "websmsc".
 * Falls back to "VERIFY-XXXXXXXXX" if no realm name is provided.
 * This is embedded in the wa.me deep link as the pre-filled message text.
 */
public class QrTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no I/O/0/1 to avoid confusion
    private static final int TOKEN_LENGTH = 9;

    /**
     * Generate a unique verification token like "WEBSMSC-A3B7C9D2E".
     * The realm name is used as the prefix for multi-tenant support.
     *
     * @param realmName the Keycloak realm name (used as token prefix)
     * @return a token in the format "{REALM}-XXXXXXXXX"
     */
    public static String generateToken(String realmName) {
        String prefix = (realmName != null && !realmName.isBlank())
                ? realmName.toUpperCase().replaceAll("[^A-Z0-9]", "") + "-"
                : "VERIFY-";
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * Build a wa.me deep link URL that, when opened, pre-fills a WhatsApp message
     * to the business number with the verification token.
     *
     * @param businessPhone the business WhatsApp number in international format without + (e.g. "14155238886")
     * @param token         the verification token (e.g. "WEBSMSC-A3B7C9D2E")
     * @return the deep link URL
     */
    public static String buildWaMeLink(String businessPhone, String token) {
        // wa.me link format: https://wa.me/{phone}?text={url_encoded_message}
        // Strip everything except digits to remove invisible unicode chars (BOM, zero-width spaces, etc.)
        String phone = businessPhone.replaceAll("[^0-9]", "");
        String encodedText = urlEncode(token);
        return String.format("https://wa.me/%s?text=%s", phone, encodedText);
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
