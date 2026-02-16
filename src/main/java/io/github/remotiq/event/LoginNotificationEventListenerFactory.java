/*
 * Copyright (c) 2026-present RemotiQ (Anshu S.)
 * SPDX-License-Identifier: MIT
 *
 * This file is part of wa2fa-meta (https://github.com/RemotiQ/wa2fa-meta).
 * Do not remove this header.
 * The copyright and permission notice must be included in all copies
 * or substantial portions of the Software. See LICENSE.
 */

package io.github.remotiq.event;

import io.github.remotiq.MessageService;
import io.github.remotiq.SmsService;
import io.github.remotiq.WhatsAppService;
import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

public class LoginNotificationEventListenerFactory implements EventListenerProviderFactory {

    public static final String PROVIDER_ID = "wa2fa-login-notification";

    private MessageService messageService;
    private String defaultLanguage;
    private String defaultCountryCode;
    private boolean enabled;
    private boolean async;

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new LoginNotificationEventListener(
                session, messageService, defaultLanguage, defaultCountryCode, enabled, async);
    }

    @Override
    public void init(Config.Scope config) {
        // Read from spi config in keycloak.conf / environment variables:
        //   spi-events-listener-wa2fa-login-notification-access-token=xxx
        //   spi-events-listener-wa2fa-login-notification-phone-number-id=xxx
        // Or from environment:
        //   KC_SPI_EVENTS_LISTENER_WA2FA_LOGIN_NOTIFICATION_ACCESS_TOKEN=xxx
        String accessToken   = resolveConfig(config, "access-token", "WA2FA_ACCESS_TOKEN");
        String phoneNumberId = resolveConfig(config, "phone-number-id", "WA2FA_PHONE_NUMBER_ID");
        String apiVersion    = resolveConfig(config, "api-version", "WA2FA_API_VERSION", "v22.0");
        String templateName  = resolveConfig(config, "template-name", "WA2FA_TEMPLATE_LOGIN", "login_notification");
        String otpTemplate   = resolveConfig(config, "otp-template", "WA2FA_TEMPLATE_OTP", "otp_message");

        this.defaultLanguage = resolveConfig(config, "default-language", "WA2FA_DEFAULT_LANGUAGE", "en");
        this.enabled = Boolean.parseBoolean(
                resolveConfig(config, "enabled", "WA2FA_LOGIN_NOTIFICATION_ENABLED", "true"));
        this.async = Boolean.parseBoolean(
                resolveConfig(config, "async", "WA2FA_LOGIN_NOTIFICATION_ASYNC", "true"));

        // SMS fallback configuration
        String smsFallbackUrl    = resolveConfig(config, "sms-fallback-url", "WA2FA_SMS_FALLBACK_URL", null);
        String smsFallbackMethod = resolveConfig(config, "sms-fallback-method", "WA2FA_SMS_FALLBACK_METHOD", "GET");

        // Build MessageService with WhatsApp + optional SMS fallback
        WhatsAppService wa = new WhatsAppService(accessToken, phoneNumberId, apiVersion);
        SmsService sms = (smsFallbackUrl != null && !smsFallbackUrl.isBlank())
                ? new SmsService(smsFallbackUrl, smsFallbackMethod)
                : null;

        // Default country code for phone validation
        String cc = resolveConfig(config, "default-country-code", "WA2FA_DEFAULT_COUNTRY_CODE", null);
        this.defaultCountryCode = (cc != null && !cc.isBlank()) ? cc.toUpperCase() : null;

        this.messageService = new MessageService(wa, sms, otpTemplate, templateName);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // no-op
    }

    @Override
    public void close() {
        // no-op
    }

    private String resolveConfig(Config.Scope config, String key, String envKey) {
        return resolveConfig(config, key, envKey, null);
    }

    private String resolveConfig(Config.Scope config, String key, String envKey, String defaultValue) {
        String val = config.get(key);
        if (val != null && !val.isBlank()) return val;

        val = System.getenv(envKey);
        if (val != null && !val.isBlank()) return val;

        return defaultValue;
    }

    private static int parseIntSafe(String val, int defaultValue) {
        if (val == null) return defaultValue;
        try { return Integer.parseInt(val); }
        catch (NumberFormatException e) { return defaultValue; }
    }
}
