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

/**
 * Parses User-Agent strings into human-readable browser + OS summaries.
 *
 * Used by login notification event listeners to include browser/device
 * details in WhatsApp/SMS login alerts.
 *
 * Example outputs:
 *   "Chrome 120 on Windows 10/11"
 *   "Safari 17 on iPhone (iOS 17.2)"
 *   "Firefox 121 on macOS 14.2"
 */
public class BrowserParser {

    private BrowserParser() {}

    /**
     * Parse User-Agent string into a human-readable browser + OS summary.
     *
     * @param ua raw User-Agent header value
     * @return e.g. "Chrome 120 on Windows 10/11"
     */
    public static String parse(String ua) {
        if (ua == null || ua.isBlank()) return "unknown";

        String browser = "Unknown Browser";
        String os = "Unknown OS";

        // --- Detect browser (order matters: Edge/Opera before Chrome) ---
        if (ua.contains("Edg/")) {
            browser = "Edge " + extractMajorVersion(ua, "Edg/");
        } else if (ua.contains("OPR/") || ua.contains("Opera")) {
            browser = "Opera " + extractMajorVersion(ua, "OPR/");
        } else if (ua.contains("Chrome/") && !ua.contains("Chromium")) {
            browser = "Chrome " + extractMajorVersion(ua, "Chrome/");
        } else if (ua.contains("Firefox/")) {
            browser = "Firefox " + extractMajorVersion(ua, "Firefox/");
        } else if (ua.contains("Safari/") && !ua.contains("Chrome")) {
            browser = "Safari " + extractMajorVersion(ua, "Version/");
        } else if (ua.contains("MSIE") || ua.contains("Trident/")) {
            browser = "Internet Explorer";
        }

        // --- Detect OS / device ---
        if (ua.contains("iPhone")) {
            String iosVer = extractIOSVersion(ua);
            os = "iPhone" + (iosVer != null ? " (iOS " + iosVer + ")" : "");
        } else if (ua.contains("iPad")) {
            String iosVer = extractIOSVersion(ua);
            os = "iPad" + (iosVer != null ? " (iPadOS " + iosVer + ")" : "");
        } else if (ua.contains("Android")) {
            os = "Android " + extractMajorVersion(ua, "Android ");
        } else if (ua.contains("Windows NT 10")) {
            os = "Windows 10/11";
        } else if (ua.contains("Windows NT 6.3")) {
            os = "Windows 8.1";
        } else if (ua.contains("Windows NT 6.1")) {
            os = "Windows 7";
        } else if (ua.contains("Windows NT")) {
            os = "Windows";
        } else if (ua.contains("Mac OS X")) {
            os = "macOS " + extractMacVersion(ua);
        } else if (ua.contains("Linux")) {
            os = "Linux";
        } else if (ua.contains("CrOS")) {
            os = "Chrome OS";
        }

        return browser + " on " + os;
    }

    private static String extractMajorVersion(String ua, String prefix) {
        int idx = ua.indexOf(prefix);
        if (idx == -1) return "";
        int start = idx + prefix.length();
        int end = start;
        while (end < ua.length() && (Character.isDigit(ua.charAt(end)) || ua.charAt(end) == '.')) {
            end++;
        }
        if (start == end) return "";
        String ver = ua.substring(start, end);
        // Return just major version for brevity
        int dot = ver.indexOf('.');
        return dot > 0 ? ver.substring(0, dot) : ver;
    }

    private static String extractIOSVersion(String ua) {
        int idx = ua.indexOf("OS ");
        if (idx == -1) return null;
        int start = idx + 3;
        int end = start;
        while (end < ua.length() && (Character.isDigit(ua.charAt(end)) || ua.charAt(end) == '_' || ua.charAt(end) == '.')) {
            end++;
        }
        if (start == end) return null;
        // iOS uses underscores: "OS 17_2_1" → "17.2.1", trim to major.minor
        String ver = ua.substring(start, end).replace('_', '.');
        String[] parts = ver.split("\\.");
        if (parts.length >= 2) return parts[0] + "." + parts[1];
        return parts[0];
    }

    private static String extractMacVersion(String ua) {
        int idx = ua.indexOf("Mac OS X ");
        if (idx == -1) return "";
        int start = idx + 9;
        int end = start;
        while (end < ua.length() && (Character.isDigit(ua.charAt(end)) || ua.charAt(end) == '_' || ua.charAt(end) == '.')) {
            end++;
        }
        if (start == end) return "";
        String ver = ua.substring(start, end).replace('_', '.');
        // Trim to major.minor
        String[] parts = ver.split("\\.");
        if (parts.length >= 2) return parts[0] + "." + parts[1];
        return parts[0];
    }
}
