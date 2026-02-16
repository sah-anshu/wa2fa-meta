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
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.AuthenticationFlowModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared configuration resolver for all wa2fa components.
 *
 * Looks up the WhatsApp OTP Authenticator's config from the realm's authentication
 * flows (Admin Console settings). Falls back to environment variables if the
 * authenticator config is not found.
 *
 * This ensures that Registration (FormAction), Required Action, and the OTP
 * Authenticator all read from the same Admin Console configuration.
 */
public class Wa2faConfig {

    private static final Logger log = Logger.getLogger(Wa2faConfig.class);

    // The provider ID registered by WhatsAppOtpAuthenticatorFactory
    private static final String WA2FA_PROVIDER_ID = "wa2fa-otp-authenticator";

    // Config keys (must match WhatsAppOtpAuthenticator constants)
    public static final String ACCESS_TOKEN = "wa2fa.accessToken";
    public static final String PHONE_NUMBER_ID = "wa2fa.phoneNumberId";
    public static final String API_VERSION = "wa2fa.apiVersion";
    public static final String OTP_LENGTH = "wa2fa.otpLength";
    public static final String OTP_EXPIRY = "wa2fa.otpExpiry";
    public static final String TEMPLATE_OTP = "wa2fa.templateOtp";
    public static final String TEMPLATE_LOGIN = "wa2fa.templateLogin";
    public static final String TEMPLATE_LOGIN_LAYOUT = "wa2fa.templateLoginLayout";
    public static final String DEFAULT_LANG = "wa2fa.defaultLanguage";
    public static final String SMS_FALLBACK_URL = "wa2fa.smsFallbackUrl";
    public static final String SMS_FALLBACK_METHOD = "wa2fa.smsFallbackMethod";
    public static final String QR_ENABLED = "wa2fa.qrEnabled";
    public static final String WEBHOOK_VERIFY_TOKEN = "wa2fa.webhookVerifyToken";
    public static final String OTP_ENABLED = "wa2fa.otpEnabled";
    public static final String DEFAULT_COUNTRY_CODE = "wa2fa.defaultCountryCode";
    public static final String APP_SECRET = "wa2fa.appSecret";

    // QR ack reply message config keys
    public static final String QR_ACK_VERIFIED = "wa2fa.qrAckVerified";
    public static final String QR_ACK_MISMATCH = "wa2fa.qrAckMismatch";
    public static final String QR_ACK_EXPIRED = "wa2fa.qrAckExpired";
    public static final String QR_ACK_NO_MATCH = "wa2fa.qrAckNoMatch";

    private final Map<String, String> config;

    private Wa2faConfig(Map<String, String> config) {
        this.config = config;
    }

    /**
     * Resolve the wa2fa configuration by scanning the realm's authentication flows
     * for the WhatsApp OTP authenticator config. Falls back to environment variables.
     *
     * @param session  Keycloak session
     * @param realm    current realm
     * @return resolved config
     */
    public static Wa2faConfig resolve(KeycloakSession session, RealmModel realm) {
        Map<String, String> cfg = findAuthenticatorConfig(realm);
        return new Wa2faConfig(cfg);
    }

    /**
     * Scan all authentication flows in the realm to find the wa2fa authenticator
     * execution and return its config map.
     */
    private static Map<String, String> findAuthenticatorConfig(RealmModel realm) {
        try {
            for (AuthenticationFlowModel flow : realm.getAuthenticationFlowsStream().toList()) {
                for (AuthenticationExecutionModel execution : realm.getAuthenticationExecutionsStream(flow.getId()).toList()) {
                    if (WA2FA_PROVIDER_ID.equals(execution.getAuthenticator())) {
                        String configId = execution.getAuthenticatorConfig();
                        if (configId != null) {
                            AuthenticatorConfigModel configModel = realm.getAuthenticatorConfigById(configId);
                            if (configModel != null && configModel.getConfig() != null) {
                                log.debugf("Found wa2fa authenticator config in flow '%s'", flow.getAlias());
                                return configModel.getConfig();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error scanning realm flows for wa2fa config, falling back to env vars", e);
        }

        log.debug("No wa2fa authenticator config found in realm flows, using env vars");
        return Map.of();
    }

    // --- Getters with env var fallback ---

    public String get(String key, String envKey, String defaultValue) {
        String val = config.get(key);
        if (val != null && !val.isBlank()) return sanitize(val);
        String envVal = env(envKey, defaultValue);
        return envVal != null ? sanitize(envVal) : null;
    }

    /**
     * Strip invisible unicode characters that can sneak in from copy-paste:
     * BOM (\uFEFF), zero-width spaces (\u200B-\u200D, \u2060), soft hyphen (\u00AD),
     * left/right-to-right marks (\u200E-\u200F), and other control characters.
     */
    private static String sanitize(String value) {
        if (value == null) return null;
        return value.replaceAll("[\\uFEFF\\u200B-\\u200D\\u200E\\u200F\\u00AD\\u2060\\u180E\\p{Cc}&&[^\\n\\r\\t]]", "").trim();
    }

    public int getInt(String key, String envKey, int defaultValue) {
        String val = config.get(key);
        if (val != null && !val.isBlank()) {
            try { return Integer.parseInt(val); }
            catch (NumberFormatException e) { /* fall through */ }
        }
        return intEnv(envKey, defaultValue);
    }

    public String accessToken() {
        return get(ACCESS_TOKEN, "WA2FA_ACCESS_TOKEN", "");
    }

    public String phoneNumberId() {
        return get(PHONE_NUMBER_ID, "WA2FA_PHONE_NUMBER_ID", "");
    }

    public String apiVersion() {
        return get(API_VERSION, "WA2FA_API_VERSION", "v22.0");
    }

    public int otpLength() {
        return getInt(OTP_LENGTH, "WA2FA_OTP_LENGTH", 6);
    }

    public int otpExpiry() {
        return getInt(OTP_EXPIRY, "WA2FA_OTP_EXPIRY", 300);
    }

    public String templateOtp() {
        return get(TEMPLATE_OTP, "WA2FA_TEMPLATE_OTP", "otp_message");
    }

    public String templateLogin() {
        return get(TEMPLATE_LOGIN, "WA2FA_TEMPLATE_LOGIN", "login_notification");
    }

    /**
     * Login notification template layout with named variables.
     * Variables: {{username}}, {{login_time}}, {{ip_address}}, {{browser}}
     */
    public String templateLoginLayout() {
        return get(TEMPLATE_LOGIN_LAYOUT, "WA2FA_TEMPLATE_LOGIN_LAYOUT",
                "New login for {{username}} at {{login_time}} from IP {{ip_address}} ({{browser}}). If this wasn't you, secure your account.");
    }

    public String defaultLanguage() {
        return get(DEFAULT_LANG, "WA2FA_DEFAULT_LANGUAGE", "en");
    }

    // --- QR Ack Reply Messages (variable: {{last4}} = last 4 digits of expected phone) ---

    public String qrAckVerified() {
        return get(QR_ACK_VERIFIED, "WA2FA_QR_ACK_VERIFIED",
                "\u2705 Login verified successfully! You may close this chat.");
    }

    public String qrAckMismatch() {
        return get(QR_ACK_MISMATCH, "WA2FA_QR_ACK_MISMATCH",
                "\u274c Verification failed. Please try from the mobile number ending in {{last4}}.");
    }

    public String qrAckExpired() {
        return get(QR_ACK_EXPIRED, "WA2FA_QR_ACK_EXPIRED",
                "\u23f0 This verification code has expired. Please request a new one from the login page.");
    }

    public String qrAckNoMatch() {
        return get(QR_ACK_NO_MATCH, "WA2FA_QR_ACK_NO_MATCH",
                "\u2753 This message was not recognised as a verification code. If you are trying to log in, please scan the QR code from the login page.");
    }

    public String smsFallbackUrl() {
        return get(SMS_FALLBACK_URL, "WA2FA_SMS_FALLBACK_URL", null);
    }

    public String smsFallbackMethod() {
        return get(SMS_FALLBACK_METHOD, "WA2FA_SMS_FALLBACK_METHOD", "GET");
    }

    /**
     * Whether QR code verification is enabled (dual method: QR + OTP).
     * Requires a configured webhook for receiving incoming WhatsApp messages.
     */
    public boolean qrEnabled() {
        String val = get(QR_ENABLED, "WA2FA_QR_ENABLED", "false");
        return "true".equalsIgnoreCase(val);
    }

    /**
     * Whether OTP sending is enabled. When false, only QR code verification is available.
     * Defaults to true for backward compatibility.
     */
    public boolean otpEnabled() {
        String val = get(OTP_ENABLED, "WA2FA_OTP_ENABLED", "true");
        return !"false".equalsIgnoreCase(val);
    }

    public String webhookVerifyToken() {
        return get(WEBHOOK_VERIFY_TOKEN, "WA2FA_WEBHOOK_VERIFY_TOKEN", "wa2fa-default-token");
    }

    /**
     * Meta App Secret for webhook payload signature verification (X-Hub-Signature-256).
     * Required for production — verifies webhook POSTs are genuinely from Meta.
     * If not configured, webhook signature validation is skipped (insecure).
     */
    public String appSecret() {
        return get(APP_SECRET, "WA2FA_APP_SECRET", null);
    }

    /**
     * Default ISO-3166 alpha-2 country code for phone number validation.
     * Used as fallback when the user doesn't provide the number in E.164 format
     * (i.e. without "+"). For example: "IN" for India, "US" for United States.
     * If null/empty, the phone number must include the "+" international prefix.
     */
    public String defaultCountryCode() {
        String val = get(DEFAULT_COUNTRY_CODE, "WA2FA_DEFAULT_COUNTRY_CODE", null);
        return (val != null && !val.isBlank()) ? val.toUpperCase() : null;
    }

    /**
     * Get the business phone number for wa.me deep links.
     * This is the phoneNumberId from WhatsApp Business (used in QR codes).
     * The actual WhatsApp number must be set separately as it may differ from the API phone number ID.
     */
    public String businessPhone() {
        return get("wa2fa.businessPhone", "WA2FA_BUSINESS_PHONE", phoneNumberId());
    }

    /**
     * Build a MessageService from this config.
     */
    public MessageService buildMessageService() {
        WhatsAppService wa = new WhatsAppService(accessToken(), phoneNumberId(), apiVersion());
        String fallbackUrl = smsFallbackUrl();
        SmsService sms = (fallbackUrl != null && !fallbackUrl.isBlank())
                ? new SmsService(fallbackUrl, smsFallbackMethod())
                : null;
        return new MessageService(wa, sms, templateOtp(), templateLogin(), templateLoginLayout());
    }

    private static String env(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

    private static int intEnv(String key, int defaultValue) {
        String val = System.getenv(key);
        if (val == null) return defaultValue;
        try { return Integer.parseInt(val); }
        catch (NumberFormatException e) { return defaultValue; }
    }
}
