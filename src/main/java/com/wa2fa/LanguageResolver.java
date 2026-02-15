package com.wa2fa;

import org.keycloak.models.UserModel;

import java.util.Map;
import java.util.Set;

public class LanguageResolver {

    private static final Set<String> SUPPORTED = Set.of("en", "hi", "es", "fr", "de", "ar", "pt");

    /**
     * Map of short language codes to Meta WhatsApp template locale codes.
     * Meta templates use specific locale codes (e.g. en_US, pt_BR) not just "en" or "pt".
     * These must match the exact language code the template was created with in Meta Business Manager.
     */
    private static final Map<String, String> META_LOCALE_MAP = Map.of(
            "en", "en_US",
            "hi", "hi",
            "es", "es",
            "fr", "fr",
            "de", "de",
            "ar", "ar",
            "pt", "pt_BR"
    );

    /**
     * Resolve the internal language code for a user.
     * Returns short codes: en, hi, es, fr, de, ar, pt.
     */
    public static String resolve(UserModel user, String defaultLanguage) {
        if (user == null) {
            return fallback(defaultLanguage);
        }

        String locale = user.getFirstAttribute("locale");
        if (locale == null || locale.isBlank()) {
            return fallback(defaultLanguage);
        }

        // Keycloak locales can be "en", "en-US", "hi-IN", etc.
        String code = locale.split("[-_]")[0].toLowerCase();
        if (SUPPORTED.contains(code)) {
            return code;
        }
        return fallback(defaultLanguage);
    }

    /**
     * Convert an internal language code to Meta WhatsApp template locale code.
     * e.g. "en" → "en_US", "pt" → "pt_BR"
     * If no mapping exists, returns the input as-is.
     */
    public static String toMetaLocale(String langCode) {
        if (langCode == null) return "en_US";
        return META_LOCALE_MAP.getOrDefault(langCode, langCode);
    }

    private static String fallback(String defaultLanguage) {
        if (defaultLanguage != null && SUPPORTED.contains(defaultLanguage)) {
            return defaultLanguage;
        }
        return "en";
    }
}
