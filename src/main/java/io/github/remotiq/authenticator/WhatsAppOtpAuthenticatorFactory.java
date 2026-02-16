/*
 * Copyright (c) 2026-present RemotiQ (Anshu S.)
 * SPDX-License-Identifier: MIT
 *
 * This file is part of wa2fa-meta (https://github.com/RemotiQ/wa2fa-meta).
 * Do not remove this header.
 * The copyright and permission notice must be included in all copies
 * or substantial portions of the Software. See LICENSE.
 */

package io.github.remotiq.authenticator;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

import java.util.List;

public class WhatsAppOtpAuthenticatorFactory implements AuthenticatorFactory {

    public static final String PROVIDER_ID = "wa2fa-otp-authenticator";

    private static final WhatsAppOtpAuthenticator SINGLETON = new WhatsAppOtpAuthenticator();

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "WhatsApp OTP (wa2fa)";
    }

    @Override
    public String getHelpText() {
        return "Sends a one-time password via WhatsApp Cloud API for two-factor authentication. Falls back to HTTP SMS when WhatsApp is unavailable.";
    }

    @Override
    public String getReferenceCategory() {
        return "otp";
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return true;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return new AuthenticationExecutionModel.Requirement[]{
                AuthenticationExecutionModel.Requirement.REQUIRED,
                AuthenticationExecutionModel.Requirement.ALTERNATIVE,
                AuthenticationExecutionModel.Requirement.DISABLED
        };
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return ProviderConfigurationBuilder.create()
                .property()
                    .name(WhatsAppOtpAuthenticator.CONFIG_ACCESS_TOKEN)
                    .label("WhatsApp Access Token")
                    .helpText("Permanent or temporary access token from Meta Business Manager")
                    .type(ProviderConfigProperty.PASSWORD)
                    .secret(true)
                    .add()
                .property()
                    .name(WhatsAppOtpAuthenticator.CONFIG_PHONE_NUMBER_ID)
                    .label("Phone Number ID")
                    .helpText("The Phone Number ID from WhatsApp Business Manager")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .add()
                .property()
                    .name(WhatsAppOtpAuthenticator.CONFIG_API_VERSION)
                    .label("Graph API Version")
                    .helpText("Meta Graph API version (e.g. v22.0). Check your app's API version at developers.facebook.com → Your App → Settings.")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .defaultValue("v22.0")
                    .add()
                .property()
                    .name(WhatsAppOtpAuthenticator.CONFIG_OTP_LENGTH)
                    .label("OTP Length")
                    .helpText("Number of digits in the OTP (4-10)")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .defaultValue("6")
                    .add()
                .property()
                    .name(WhatsAppOtpAuthenticator.CONFIG_OTP_EXPIRY)
                    .label("OTP Expiry (seconds)")
                    .helpText("How long the OTP remains valid")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .defaultValue("300")
                    .add()
                .property()
                    .name(WhatsAppOtpAuthenticator.CONFIG_TEMPLATE_NAME)
                    .label("OTP Template Name")
                    .helpText("Name of the WhatsApp authentication template for OTP (must be pre-approved in Meta Business). " +
                              "Template body format: {{1}} is your verification code. For your security, do not share this code. Expires in {{2}} minutes.")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .defaultValue("otp_message")
                    .add()
                .property()
                    .name(WhatsAppOtpAuthenticator.CONFIG_TEMPLATE_LOGIN)
                    .label("Login Notification Template Name")
                    .helpText("Name of the WhatsApp message template for login notifications (must be pre-approved in Meta Business). " +
                              "Leave empty to use default 'login_notification'.")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .defaultValue("login_notification")
                    .add()
                .property()
                    .name(WhatsAppOtpAuthenticator.CONFIG_TEMPLATE_LOGIN_LAYOUT)
                    .label("Login Notification Template Layout")
                    .helpText("Define the template body layout for login notifications. " +
                              "Variable parameters must be lowercase characters, underscores and numbers " +
                              "with two sets of curly brackets. Available variables: " +
                              "{{username}} - the user's login name, " +
                              "{{login_time}} - timestamp of the login, " +
                              "{{ip_address}} - IP address of the login, " +
                              "{{browser}} - browser/device info. " +
                              "Example: New login for {{username}} at {{login_time}} from IP {{ip_address}} ({{browser}}). If this wasn't you, secure your account.")
                    .type(ProviderConfigProperty.TEXT_TYPE)
                    .defaultValue("New login for {{username}} at {{login_time}} from IP {{ip_address}} ({{browser}}). If this wasn't you, secure your account.")
                    .add()
                .property()
                    .name(WhatsAppOtpAuthenticator.CONFIG_DEFAULT_LANG)
                    .label("Default Language")
                    .helpText("Fallback language when user's locale is not supported")
                    .type(ProviderConfigProperty.LIST_TYPE)
                    .options(List.of("en", "hi", "es", "fr", "de", "ar", "pt"))
                    .defaultValue("en")
                    .add()
                // --- SMS Fallback Configuration ---
                .property()
                    .name(WhatsAppOtpAuthenticator.CONFIG_SMS_FALLBACK_URL)
                    .label("SMS Fallback URL")
                    .helpText("Base URL for SMS fallback when WhatsApp fails. Include auth/API keys as URL parameters " +
                              "(e.g. https://sms-gw.com/send?apiKey=xxx). The plugin appends to, content, and coding params. " +
                              "Leave empty to disable SMS fallback.")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .add()
                .property()
                    .name(WhatsAppOtpAuthenticator.CONFIG_SMS_FALLBACK_METHOD)
                    .label("SMS Fallback HTTP Method")
                    .helpText("HTTP method used to call the SMS fallback URL: GET or POST (parameters are always sent as URL query params).")
                    .type(ProviderConfigProperty.LIST_TYPE)
                    .options(List.of("GET", "POST"))
                    .defaultValue("GET")
                    .add()
                // --- QR Code Verification Configuration ---
                .property()
                    .name(WhatsAppOtpAuthenticator.CONFIG_QR_ENABLED)
                    .label("Enable QR Code Verification")
                    .helpText("Enable 'Scan QR Code' as an alternative verification method. " +
                              "User scans QR to open WhatsApp and send a verification message. " +
                              "Requires webhook in Meta Business Manager. " +
                              "Callback URL: https://{your-keycloak-host}/realms/{realm-name}/wa2fa/webhook")
                    .type(ProviderConfigProperty.BOOLEAN_TYPE)
                    .defaultValue("false")
                    .add()
                .property()
                    .name(WhatsAppOtpAuthenticator.CONFIG_OTP_ENABLED)
                    .label("Enable Sending OTP")
                    .helpText("When enabled, an OTP code is sent via WhatsApp (and SMS fallback) for verification. " +
                              "When disabled, only QR Code verification is available (requires 'Enable QR Code Verification' to be ON). " +
                              "You can enable both for dual-method verification.")
                    .type(ProviderConfigProperty.BOOLEAN_TYPE)
                    .defaultValue("true")
                    .add()
                .property()
                    .name(WhatsAppOtpAuthenticator.CONFIG_BUSINESS_PHONE)
                    .label("Business WhatsApp Number")
                    .helpText("Your WhatsApp Business phone number in international format without + (e.g. 14155238886). " +
                              "Used in QR code deep links (wa.me/{number}). Required if QR verification is enabled.")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .add()
                .property()
                    .name(WhatsAppOtpAuthenticator.CONFIG_WEBHOOK_VERIFY_TOKEN)
                    .label("Webhook Verify Token")
                    .helpText("Secret token for Meta webhook verification (hub.verify_token). " +
                              "Must match the token configured in Meta Business Manager → WhatsApp → Configuration → Webhook. " +
                              "Webhook URL for this realm: https://{your-keycloak-host}/realms/{realm-name}/wa2fa/webhook " +
                              "Example: https://sso.example.com/realms/myrealm/wa2fa/webhook")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .defaultValue("wa2fa-default-token")
                    .add()
                .property()
                    .name("wa2fa.appSecret")
                    .label("Meta App Secret")
                    .helpText("Meta App Secret for verifying webhook payload signatures (X-Hub-Signature-256). " +
                              "Found in Meta for Developers → App Settings → Basic → App Secret. " +
                              "Required for production to prevent forged webhook requests. Leave empty to skip signature validation.")
                    .type(ProviderConfigProperty.PASSWORD)
                    .secret(true)
                    .add()
                .property()
                    .name(WhatsAppOtpAuthenticator.CONFIG_MAX_RESEND)
                    .label("Max OTP Resend")
                    .helpText("Maximum number of times a user can resend the OTP code per login session (0 = unlimited)")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .defaultValue("3")
                    .add()
                .property()
                    .name(WhatsAppOtpAuthenticator.CONFIG_DEFAULT_COUNTRY_CODE)
                    .label("Default Country Code (ISO2)")
                    .helpText("ISO-3166 alpha-2 country code (e.g. IN, US, DE) used when the phone number is entered " +
                              "without the '+' international prefix. The number is first tried as-is; if parsing fails, " +
                              "this country code is used as fallback for libphonenumber validation. " +
                              "Leave empty to require '+' prefix on all numbers.")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .add()
                // --- QR Ack Reply Messages ---
                .property()
                    .name(WhatsAppOtpAuthenticator.CONFIG_QR_ACK_VERIFIED)
                    .label("QR Reply: Verified")
                    .helpText("Reply message sent when QR code verification succeeds. " +
                              "Example: \u2705 Login verified successfully! You may close this chat.")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .defaultValue("\u2705 Login verified successfully! You may close this chat.")
                    .add()
                .property()
                    .name(WhatsAppOtpAuthenticator.CONFIG_QR_ACK_MISMATCH)
                    .label("QR Reply: Phone Mismatch")
                    .helpText("Reply message sent when QR code is scanned from a wrong phone number. " +
                              "Variable: {{last4}} = last 4 digits of the expected phone number. " +
                              "Example: \u274c Verification failed. Please try from the number ending in {{last4}}.")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .defaultValue("\u274c Verification failed. Please try from the mobile number ending in {{last4}}.")
                    .add()
                .property()
                    .name(WhatsAppOtpAuthenticator.CONFIG_QR_ACK_EXPIRED)
                    .label("QR Reply: Expired")
                    .helpText("Reply message sent when the QR verification code has expired. " +
                              "Example: \u23f0 This verification code has expired. Please request a new one from the login page.")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .defaultValue("\u23f0 This verification code has expired. Please request a new one from the login page.")
                    .add()
                .property()
                    .name(WhatsAppOtpAuthenticator.CONFIG_QR_ACK_NO_MATCH)
                    .label("QR Reply: No Match")
                    .helpText("Reply message sent when the message is not recognised as a verification code. " +
                              "Example: \u2753 This message was not recognised as a verification code.")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .defaultValue("\u2753 This message was not recognised as a verification code. If you are trying to log in, please scan the QR code from the login page.")
                    .add()
                .build();
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return SINGLETON;
    }

    @Override
    public void init(Config.Scope config) {
        // no-op
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // no-op
    }

    @Override
    public void close() {
        // no-op
    }
}
