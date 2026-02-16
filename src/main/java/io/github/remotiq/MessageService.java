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

import java.util.Map;

/**
 * Unified message delivery: WhatsApp first, HTTP SMS fallback on failure.
 *
 * Returns a {@link SendResult} with details about which channel was used
 * and why it failed, so callers can log to Keycloak Events.
 */
public class MessageService {

    private static final Logger log = Logger.getLogger(MessageService.class);

    private final WhatsAppService whatsApp;
    private final SmsService sms;            // null if no fallback configured

    // Template names
    private final String otpTemplate;
    private final String loginTemplate;
    private final String loginLayout;        // configurable login notification layout for SMS fallback

    // SMS fallback text templates (plain text, no HTML)
    // Format matches Meta's authentication template standard: code first, security warning, expiry
    private static final Map<String, String> OTP_SMS = Map.of(
            "en", "%s is your verification code. For your security, do not share this code. Expires in 5 minutes.",
            "hi", "%s \u0906\u092a\u0915\u093e \u0938\u0924\u094d\u092f\u093e\u092a\u0928 \u0915\u094b\u0921 \u0939\u0948\u0964 \u0905\u092a\u0928\u0940 \u0938\u0941\u0930\u0915\u094d\u0937\u093e \u0915\u0947 \u0932\u093f\u090f \u092f\u0939 \u0915\u094b\u0921 \u0915\u093f\u0938\u0940 \u0915\u094b \u0928 \u092c\u0924\u093e\u090f\u0902\u0964 5 \u092e\u093f\u0928\u091f \u092e\u0947\u0902 \u0938\u092e\u093e\u092a\u094d\u0924 \u0939\u094b\u0917\u093e\u0964",
            "es", "%s es tu codigo de verificacion. Por tu seguridad, no compartas este codigo. Caduca en 5 minutos.",
            "fr", "%s est votre code de verification. Pour votre securite, ne partagez pas ce code. Expire dans 5 minutes.",
            "de", "%s ist Ihr Verifizierungscode. Zu Ihrer Sicherheit, teilen Sie diesen Code nicht. Laeuft in 5 Minuten ab.",
            "ar", "%s \u0647\u0648 \u0631\u0645\u0632 \u0627\u0644\u062a\u062d\u0642\u0642 \u0627\u0644\u062e\u0627\u0635 \u0628\u0643. \u0644\u0623\u0645\u0627\u0646\u0643 \u0644\u0627 \u062a\u0634\u0627\u0631\u0643 \u0647\u0630\u0627 \u0627\u0644\u0631\u0645\u0632. \u064a\u0646\u062a\u0647\u064a \u062e\u0644\u0627\u0644 5 \u062f\u0642\u0627\u0626\u0642.",
            "pt", "%s e seu codigo de verificacao. Para sua seguranca, nao compartilhe este codigo. Expira em 5 minutos."
    );

    // Login SMS with browser details: %1=username, %2=timestamp, %3=IP, %4=browser
    private static final Map<String, String> LOGIN_SMS = Map.of(
            "en", "Login alert: %s at %s from IP %s (%s). If this wasn't you, secure your account.",
            "hi", "%s \u0915\u0947 \u0932\u093f\u090f %s \u092a\u0930 IP %s (%s) \u0938\u0947 \u0928\u092f\u093e \u0932\u0949\u0917\u093f\u0928\u0964 \u092f\u0926\u093f \u092f\u0939 \u0906\u092a \u0928\u0939\u0940\u0902 \u0925\u0947 \u0924\u094b \u0905\u092a\u0928\u093e \u0916\u093e\u0924\u093e \u0938\u0941\u0930\u0915\u094d\u0937\u093f\u0924 \u0915\u0930\u0947\u0902\u0964",
            "es", "Alerta: %s a las %s desde IP %s (%s). Si no fuiste tu, asegura tu cuenta.",
            "fr", "Alerte: %s a %s depuis IP %s (%s). Securisez votre compte si ce n'etait pas vous.",
            "de", "Warnung: %s um %s von IP %s (%s). Sichern Sie Ihr Konto falls Sie es nicht waren.",
            "ar", "\u062a\u0646\u0628\u064a\u0647: %s \u0641\u064a %s \u0645\u0646 IP %s (%s). \u0625\u0630\u0627 \u0644\u0645 \u064a\u0643\u0646 \u0647\u0630\u0627 \u0623\u0646\u062a \u0642\u0645 \u0628\u062a\u0623\u0645\u064a\u0646 \u062d\u0633\u0627\u0628\u0643.",
            "pt", "Alerta: %s as %s do IP %s (%s). Se nao foi voce, proteja sua conta."
    );

    /**
     * Result of a message send attempt, capturing which channels were tried
     * and the outcome.
     */
    public static class SendResult {
        private final boolean success;
        private final String channel;          // "whatsapp", "sms", or "none"
        private final boolean whatsAppAttempted;
        private final boolean whatsAppSuccess;
        private final boolean smsAttempted;
        private final boolean smsSuccess;
        private final String failureReason;    // human-readable reason for failure

        public SendResult(boolean success, String channel,
                           boolean whatsAppAttempted, boolean whatsAppSuccess,
                           boolean smsAttempted, boolean smsSuccess,
                           String failureReason) {
            this.success = success;
            this.channel = channel;
            this.whatsAppAttempted = whatsAppAttempted;
            this.whatsAppSuccess = whatsAppSuccess;
            this.smsAttempted = smsAttempted;
            this.smsSuccess = smsSuccess;
            this.failureReason = failureReason;
        }

        public boolean isSuccess()          { return success; }
        public String getChannel()          { return channel; }
        public boolean isWhatsAppAttempted() { return whatsAppAttempted; }
        public boolean isWhatsAppSuccess()  { return whatsAppSuccess; }
        public boolean isSmsAttempted()     { return smsAttempted; }
        public boolean isSmsSuccess()       { return smsSuccess; }
        public String getFailureReason()    { return failureReason; }
    }

    public MessageService(WhatsAppService whatsApp,
                           SmsService sms,
                           String otpTemplate,
                           String loginTemplate) {
        this(whatsApp, sms, otpTemplate, loginTemplate, null);
    }

    public MessageService(WhatsAppService whatsApp,
                           SmsService sms,
                           String otpTemplate,
                           String loginTemplate,
                           String loginLayout) {
        this.whatsApp = whatsApp;
        this.sms = sms;
        this.otpTemplate = otpTemplate;
        this.loginTemplate = loginTemplate;
        this.loginLayout = loginLayout;
    }

    /**
     * Send OTP: WhatsApp first, SMS fallback on failure.
     * Returns a SendResult with details about the delivery attempt.
     */
    public SendResult sendOtp(String phone, String lang, String otpCode) {
        return sendOtp(phone, lang, otpCode, null);
    }

    /**
     * Send OTP: WhatsApp template first, SMS fallback on failure.
     * Note: No realm footer is appended — Meta templates are pre-approved fixed content.
     */
    public SendResult sendOtp(String phone, String lang, String otpCode, String realmDisplayName) {
        boolean waSent = whatsApp.sendAuthTemplateMessage(phone, otpTemplate, lang, otpCode);

        if (waSent) {
            return new SendResult(true, "whatsapp", true, true, false, false, null);
        }

        // WhatsApp failed — try SMS fallback with reason
        String waError = whatsApp.getLastError();
        if (sms != null) {
            log.infof("WhatsApp failed for %s, falling back to SMS (reason: %s)", phone, waError);
            String text = String.format(OTP_SMS.getOrDefault(lang, OTP_SMS.get("en")), otpCode);
            boolean smsSent = sms.send(phone, text, waError);

            if (smsSent) {
                return new SendResult(true, "sms", true, false, true, true, null);
            } else {
                return new SendResult(false, "none", true, false, true, false,
                        "WhatsApp API failed; SMS fallback also failed");
            }
        }

        // No SMS fallback configured
        return new SendResult(false, "none", true, false, false, false,
                "WhatsApp API failed; no SMS fallback configured");
    }

    /**
     * Send login notification: WhatsApp first, SMS fallback on failure.
     * Includes browser/device details for security awareness.
     */
    public SendResult sendLoginNotification(String phone, String lang,
                                             String username, String timestamp,
                                             String ip, String browser) {
        return sendLoginNotification(phone, lang, username, timestamp, ip, browser, null);
    }

    /**
     * Send login notification: WhatsApp template first, SMS fallback on failure.
     * Note: No realm footer is appended — Meta templates are pre-approved fixed content.
     */
    public SendResult sendLoginNotification(String phone, String lang,
                                             String username, String timestamp,
                                             String ip, String browser,
                                             String realmDisplayName) {
        String safeBrowser = browser != null ? browser : "unknown";
        boolean waSent = whatsApp.sendTemplateMessage(phone, loginTemplate, lang,
                username, timestamp, ip, safeBrowser);

        if (waSent) {
            return new SendResult(true, "whatsapp", true, true, false, false, null);
        }

        String waError = whatsApp.getLastError();
        if (sms != null) {
            log.infof("WhatsApp failed for %s, falling back to SMS for login notification (reason: %s)", phone, waError);
            String text;
            if (loginLayout != null && !loginLayout.isBlank()) {
                text = loginLayout
                        .replace("{{username}}", username)
                        .replace("{{login_time}}", timestamp)
                        .replace("{{ip_address}}", ip)
                        .replace("{{browser}}", safeBrowser);
            } else {
                text = String.format(LOGIN_SMS.getOrDefault(lang, LOGIN_SMS.get("en")),
                        username, timestamp, ip, safeBrowser);
            }
            boolean smsSent = sms.send(phone, text, waError);

            if (smsSent) {
                return new SendResult(true, "sms", true, false, true, true, null);
            } else {
                return new SendResult(false, "none", true, false, true, false,
                        "WhatsApp API failed; SMS fallback also failed for login notification");
            }
        }

        return new SendResult(false, "none", true, false, false, false,
                "WhatsApp API failed; no SMS fallback configured for login notification");
    }

    /**
     * @deprecated Use {@link #sendLoginNotification(String, String, String, String, String, String)} instead.
     */
    @Deprecated
    public SendResult sendLoginNotification(String phone, String lang,
                                             String username, String timestamp, String ip) {
        return sendLoginNotification(phone, lang, username, timestamp, ip, "unknown");
    }

    /**
     * Build a MessageService from config/env vars.
     */
    public static MessageService fromEnv() {
        String accessToken  = env("WA2FA_ACCESS_TOKEN", "");
        String phoneId      = env("WA2FA_PHONE_NUMBER_ID", "");
        String apiVersion   = env("WA2FA_API_VERSION", "v22.0");
        String otpTpl       = env("WA2FA_TEMPLATE_OTP", "otp_message");
        String loginTpl     = env("WA2FA_TEMPLATE_LOGIN", "login_notification");
        String loginLayout  = env("WA2FA_TEMPLATE_LOGIN_LAYOUT", null);
        String smsFallback  = env("WA2FA_SMS_FALLBACK_URL", null);
        String smsMethod    = env("WA2FA_SMS_FALLBACK_METHOD", "GET");

        WhatsAppService wa = new WhatsAppService(accessToken, phoneId, apiVersion);
        SmsService smsService = (smsFallback != null && !smsFallback.isBlank())
                ? new SmsService(smsFallback, smsMethod)
                : null;

        return new MessageService(wa, smsService, otpTpl, loginTpl, loginLayout);
    }

    /**
     * Build a MessageService from authenticator config map (admin console settings).
     */
    public static MessageService fromConfig(Map<String, String> cfg) {
        String accessToken  = cfg.getOrDefault("wa2fa.accessToken", "");
        String phoneId      = cfg.getOrDefault("wa2fa.phoneNumberId", "");
        String apiVersion   = cfg.getOrDefault("wa2fa.apiVersion", "v22.0");
        String otpTpl       = cfg.getOrDefault("wa2fa.templateOtp", "otp_message");
        String loginTpl     = cfg.getOrDefault("wa2fa.templateLogin", "login_notification");
        String loginLayout  = cfg.getOrDefault("wa2fa.templateLoginLayout", env("WA2FA_TEMPLATE_LOGIN_LAYOUT", null));
        String smsFallback  = cfg.getOrDefault("wa2fa.smsFallbackUrl", env("WA2FA_SMS_FALLBACK_URL", null));
        String smsMethod    = cfg.getOrDefault("wa2fa.smsFallbackMethod", env("WA2FA_SMS_FALLBACK_METHOD", "GET"));

        WhatsAppService wa = new WhatsAppService(accessToken, phoneId, apiVersion);
        SmsService smsService = (smsFallback != null && !smsFallback.isBlank())
                ? new SmsService(smsFallback, smsMethod)
                : null;

        return new MessageService(wa, smsService, otpTpl, loginTpl, loginLayout);
    }

    private static String env(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }
}
