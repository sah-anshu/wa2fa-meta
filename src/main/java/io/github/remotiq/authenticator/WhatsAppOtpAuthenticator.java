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

import io.github.remotiq.LanguageResolver;
import io.github.remotiq.MessageService;
import io.github.remotiq.OtpService;
import io.github.remotiq.PhoneNumberValidator;
import io.github.remotiq.qr.QrTokenService;
import io.github.remotiq.qr.VerificationStore;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.Map;

public class WhatsAppOtpAuthenticator implements Authenticator {

    private static final Logger log = Logger.getLogger(WhatsAppOtpAuthenticator.class);

    static final String CONFIG_ACCESS_TOKEN = "wa2fa.accessToken";
    static final String CONFIG_PHONE_NUMBER_ID = "wa2fa.phoneNumberId";
    static final String CONFIG_API_VERSION = "wa2fa.apiVersion";
    static final String CONFIG_OTP_LENGTH = "wa2fa.otpLength";
    static final String CONFIG_OTP_EXPIRY = "wa2fa.otpExpiry";
    static final String CONFIG_TEMPLATE_NAME = "wa2fa.templateOtp";
    static final String CONFIG_TEMPLATE_LOGIN = "wa2fa.templateLogin";
    static final String CONFIG_TEMPLATE_LOGIN_LAYOUT = "wa2fa.templateLoginLayout";
    static final String CONFIG_DEFAULT_LANG = "wa2fa.defaultLanguage";

    // SMS fallback config keys
    static final String CONFIG_SMS_FALLBACK_URL = "wa2fa.smsFallbackUrl";
    static final String CONFIG_SMS_FALLBACK_METHOD = "wa2fa.smsFallbackMethod";

    // QR code verification config keys
    static final String CONFIG_QR_ENABLED = "wa2fa.qrEnabled";
    static final String CONFIG_BUSINESS_PHONE = "wa2fa.businessPhone";
    static final String CONFIG_WEBHOOK_VERIFY_TOKEN = "wa2fa.webhookVerifyToken";
    static final String CONFIG_OTP_ENABLED = "wa2fa.otpEnabled";
    static final String CONFIG_MAX_RESEND = "wa2fa.maxResend";
    static final String CONFIG_DEFAULT_COUNTRY_CODE = "wa2fa.defaultCountryCode";

    // QR ack reply message templates
    static final String CONFIG_QR_ACK_VERIFIED = "wa2fa.qrAckVerified";
    static final String CONFIG_QR_ACK_MISMATCH = "wa2fa.qrAckMismatch";
    static final String CONFIG_QR_ACK_EXPIRED = "wa2fa.qrAckExpired";
    static final String CONFIG_QR_ACK_NO_MATCH = "wa2fa.qrAckNoMatch";

    private static final String PHONE_ATTR = "phoneNumber";
    private static final String FTL = "wa2fa-otp.ftl";
    private static final String SESSION_QR_TOKEN = "wa2fa_qr_token";
    private static final String SESSION_RESEND_COUNT = "wa2fa_resend_count";

    private final OtpService otpService = new OtpService();

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        UserModel user = context.getUser();
        String rawPhone = user.getFirstAttribute(PHONE_ATTR);
        String phoneVerified = user.getFirstAttribute("phoneNumberVerified");

        // If user has no phone or phone not verified, skip OTP 2FA.
        // The wa2fa-verify-phone Required Action (added by setRequiredActions)
        // will handle phone collection and verification after login.
        if (rawPhone == null || rawPhone.isBlank()) {
            log.infof("User %s has no phone number, skipping OTP — Required Action will handle it", user.getUsername());
            context.success();
            return;
        }

        if (!"true".equals(phoneVerified)) {
            log.infof("User %s phone not verified, skipping OTP — Required Action will handle it", user.getUsername());
            context.success();
            return;
        }

        // Validate phone number with libphonenumber (use configured default country code)
        Map<String, String> cfg = configMap(context);
        String countryCode = resolveCountryCode(cfg);
        PhoneNumberValidator.ValidationResult vr = PhoneNumberValidator.validate(rawPhone, countryCode);
        if (!vr.valid()) {
            log.warnf("User %s has invalid phone number: %s, skipping OTP", user.getUsername(), vr.errorKey());
            context.success();
            return;
        }

        String phone = vr.e164();

        // On page refresh: reuse existing QR token / OTP if still valid
        AuthenticationSessionModel session = context.getAuthenticationSession();
        String existingToken = session.getAuthNote(SESSION_QR_TOKEN);
        if (existingToken != null) {
            VerificationStore.PendingVerification pv =
                    VerificationStore.getInstance().getByToken(existingToken);
            if (pv != null && !pv.isExpired()) {
                // Token still valid — re-render form without regenerating
                boolean otpAlreadySent = session.getAuthNote("wa2fa_otp_code") != null;
                log.debugf("Page refresh: reusing existing QR token %s (otpSent=%s)", existingToken, otpAlreadySent);
                Response challenge = buildOtpForm(context, null, false, otpAlreadySent);
                context.challenge(challenge);
                return;
            }
            // Token expired — clean up and fall through to generate new one
            cleanupQrToken(session);
        }

        // Check feature toggles
        boolean qrEnabled = "true".equalsIgnoreCase(cfg.getOrDefault(CONFIG_QR_ENABLED, "false"));
        boolean otpEnabled = !"false".equalsIgnoreCase(cfg.getOrDefault(CONFIG_OTP_ENABLED, "true"));

        if (qrEnabled && !otpEnabled) {
            // QR-only mode: generate QR token, no OTP sent
            generateQrTokenIfEnabled(context, phone);
            Response challenge = buildOtpForm(context, null, false, false);
            context.challenge(challenge);
        } else if (qrEnabled) {
            // Dual mode (QR + OTP): generate QR token, don't auto-send OTP (user clicks "Send OTP" tab)
            generateQrTokenIfEnabled(context, phone);
            Response challenge = buildOtpForm(context, null, false, false);
            context.challenge(challenge);
        } else if (otpEnabled) {
            // OTP-only mode: send OTP immediately (only if not already sent)
            String existingOtp = session.getAuthNote("wa2fa_otp_code");
            if (existingOtp != null) {
                // OTP already sent and stored — reuse on page refresh
                log.debugf("Page refresh: reusing existing OTP for %s", user.getUsername());
                generateQrTokenIfEnabled(context, phone);
                Response challenge = buildOtpForm(context, null, false, true);
                context.challenge(challenge);
            } else if (sendOtp(context, user, phone)) {
                generateQrTokenIfEnabled(context, phone);
                Response challenge = buildOtpForm(context, null, false, true);
                context.challenge(challenge);
            } else {
                context.failure(AuthenticationFlowError.INTERNAL_ERROR);
            }
        } else {
            // Both disabled — misconfiguration, log warning and fail
            log.warn("Both OTP and QR verification are disabled — cannot authenticate. Enable at least one method.");
            context.failure(AuthenticationFlowError.INTERNAL_ERROR);
        }
    }

    private static final String SESSION_ACTIVE_TAB = "wa2fa_active_tab";

    @Override
    public void action(AuthenticationFlowContext context) {
        MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();

        // Persist active tab selection across form submissions
        String activeTab = form.getFirst("activeTab");
        if (activeTab != null && !activeTab.isBlank()) {
            context.getAuthenticationSession().setAuthNote(SESSION_ACTIVE_TAB, activeTab);
        }

        // Check if OTP sending is enabled
        boolean otpEnabled = !"false".equalsIgnoreCase(configMap(context).getOrDefault(CONFIG_OTP_ENABLED, "true"));

        // Handle "Send OTP" from OTP tab (lazy send when QR is default)
        if (form.containsKey("sendOtpTab")) {
            if (!otpEnabled) {
                log.warn("OTP send requested but OTP is disabled");
                Response challenge = buildOtpForm(context, null, false, false);
                context.challenge(challenge);
                return;
            }
            UserModel user = context.getUser();
            String rawPhone = user.getFirstAttribute(PHONE_ATTR);
            String phone = PhoneNumberValidator.toE164(rawPhone, resolveCountryCode(configMap(context)));
            if (phone != null) {
                int maxResend = configInt(context, CONFIG_MAX_RESEND, 3);
                int resendCount = getResendCount(context);
                if (maxResend > 0 && resendCount >= maxResend) {
                    log.infof("Max resend limit (%d) reached for %s", maxResend, user.getUsername());
                    Response challenge = buildOtpForm(context, "wa2fa.otp.resendLimitReached", true, true);
                    context.challenge(challenge);
                    return;
                }
                incrementResendCount(context);
                int expiry = configInt(context, CONFIG_OTP_EXPIRY, 300);
                if (!otpService.isOtpStillValid(context.getAuthenticationSession(), expiry)) {
                    sendOtp(context, user, phone);
                } else {
                    log.infof("OTP still valid for %s, resending same code via sendOtpTab", user.getUsername());
                    resendExistingOtp(context, user, phone);
                }
                generateQrTokenIfEnabled(context, phone);
            }
            Response challenge = buildOtpForm(context, "wa2fa.otp.sent", true, true);
            context.challenge(challenge);
            return;
        }

        // Handle resend — reuse existing OTP if still valid
        if (form.containsKey("resend")) {
            if (!otpEnabled) {
                log.warn("OTP resend requested but OTP is disabled");
                Response challenge = buildOtpForm(context, null, false, false);
                context.challenge(challenge);
                return;
            }
            UserModel user = context.getUser();
            String rawPhone = user.getFirstAttribute(PHONE_ATTR);
            String phone = PhoneNumberValidator.toE164(rawPhone, resolveCountryCode(configMap(context)));
            if (phone != null) {
                int maxResend = configInt(context, CONFIG_MAX_RESEND, 3);
                int resendCount = getResendCount(context);
                if (maxResend > 0 && resendCount >= maxResend) {
                    log.infof("Max resend limit (%d) reached for %s", maxResend, user.getUsername());
                    Response challenge = buildOtpForm(context, "wa2fa.otp.resendLimitReached", true, true);
                    context.challenge(challenge);
                    return;
                }
                incrementResendCount(context);
                int expiry = configInt(context, CONFIG_OTP_EXPIRY, 300);
                if (!otpService.isOtpStillValid(context.getAuthenticationSession(), expiry)) {
                    log.infof("OTP expired for %s, generating new code on resend", user.getUsername());
                    sendOtp(context, user, phone);
                } else {
                    log.infof("OTP still valid for %s, resending same code", user.getUsername());
                    resendExistingOtp(context, user, phone);
                }
                generateQrTokenIfEnabled(context, phone);
            }
            Response challenge = buildOtpForm(context, "wa2fa.otp.resent", true, true);
            context.challenge(challenge);
            return;
        }

        // Handle QR verified (submitted by JavaScript polling)
        if (form.containsKey("qrVerified")) {
            handleQrVerified(context);
            return;
        }

        // Check lockout before processing OTP
        if (otpService.isLockedOut(context.getAuthenticationSession())) {
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS,
                    buildOtpForm(context, "wa2fa.otp.tooManyAttempts", false, true));
            return;
        }

        // Validate OTP
        String otp = form.getFirst("otp");
        if (otp == null || otp.isBlank()) {
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS,
                    buildOtpForm(context, "wa2fa.otp.missing", false, true));
            return;
        }

        int expiry = configInt(context, CONFIG_OTP_EXPIRY, 300);
        if (otpService.validateOtp(context.getAuthenticationSession(), otp.trim(), expiry)) {
            cleanupQrToken(context.getAuthenticationSession());
            context.success();
        } else {
            // Check if lockout was just triggered by this failed attempt
            if (otpService.isLockedOut(context.getAuthenticationSession())) {
                log.warnf("User %s locked out after too many OTP attempts", context.getUser().getUsername());
                context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS,
                        buildOtpForm(context, "wa2fa.otp.tooManyAttempts", false, true));
            } else {
                context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS,
                        buildOtpForm(context, "wa2fa.otp.invalid", false, true));
            }
        }
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        String phone = user.getFirstAttribute(PHONE_ATTR);
        String verified = user.getFirstAttribute("phoneNumberVerified");
        return phone != null && !phone.isBlank() && "true".equals(verified);
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        if (!configuredFor(session, realm, user)) {
            user.addRequiredAction("wa2fa-verify-phone");
        }
    }

    @Override
    public void close() {
        // no-op
    }

    // --- QR Code Verified ---

    private void handleQrVerified(AuthenticationFlowContext context) {
        AuthenticationSessionModel session = context.getAuthenticationSession();
        String token = session.getAuthNote(SESSION_QR_TOKEN);

        if (token == null) {
            log.warn("QR verified submitted but no token in session");
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS,
                    buildOtpForm(context, "wa2fa.otp.invalid", false, false));
            return;
        }

        VerificationStore.PendingVerification pv = VerificationStore.getInstance().getByToken(token);
        if (pv == null || !pv.isVerified()) {
            log.warnf("QR verification not confirmed for token %s", token);
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS,
                    buildOtpForm(context, "wa2fa.qr.notVerified", false, false));
            return;
        }

        cleanupQrToken(session);
        context.success();
    }

    // --- QR helpers ---

    private void generateQrTokenIfEnabled(AuthenticationFlowContext context, String phone) {
        Map<String, String> cfg = configMap(context);
        boolean qrEnabled = "true".equalsIgnoreCase(cfg.getOrDefault(CONFIG_QR_ENABLED, "false"));
        if (!qrEnabled) return;

        String businessPhone = cfg.get(CONFIG_BUSINESS_PHONE);
        if (businessPhone == null || businessPhone.isBlank()) {
            log.warn("QR enabled but no business phone configured — skipping QR");
            return;
        }

        // Reuse existing token if still valid (prevents regeneration on page refresh)
        AuthenticationSessionModel session = context.getAuthenticationSession();
        String existing = session.getAuthNote(SESSION_QR_TOKEN);
        if (existing != null) {
            VerificationStore.PendingVerification pv =
                    VerificationStore.getInstance().getByToken(existing);
            if (pv != null && !pv.isExpired()) {
                log.debugf("Reusing existing QR token %s", existing);
                return;
            }
        }

        String token = QrTokenService.generateToken(context.getRealm().getName());
        int expiry = configInt(context, CONFIG_OTP_EXPIRY, 300);

        String sessionId = session.getParentSession().getId();
        VerificationStore.getInstance().createPending(token, phone, expiry, sessionId);
        session.setAuthNote(SESSION_QR_TOKEN, token);

        log.infof("Generated QR token %s for phone %s (login 2FA)", token, phone);
    }

    private void cleanupQrToken(AuthenticationSessionModel session) {
        String token = session.getAuthNote(SESSION_QR_TOKEN);
        if (token != null) {
            String sessionId = session.getParentSession().getId();
            VerificationStore.getInstance().remove(sessionId);
            session.removeAuthNote(SESSION_QR_TOKEN);
        }
    }

    // --- Form builder ---

    private Response buildOtpForm(AuthenticationFlowContext context, String messageKey, boolean isInfo, boolean otpSent) {
        var formBuilder = context.form();

        if (messageKey != null) {
            if (isInfo) {
                formBuilder.setInfo(messageKey);
            } else {
                formBuilder.setError(messageKey);
            }
        }

        // Feature toggle attributes
        Map<String, String> cfg = configMap(context);
        boolean qrEnabled = "true".equalsIgnoreCase(cfg.getOrDefault(CONFIG_QR_ENABLED, "false"));
        boolean otpEnabledFlag = !"false".equalsIgnoreCase(cfg.getOrDefault(CONFIG_OTP_ENABLED, "true"));
        formBuilder.setAttribute("qrEnabled", qrEnabled);
        formBuilder.setAttribute("otpEnabled", otpEnabledFlag);

        formBuilder.setAttribute("otpSent", otpSent);

        // Pass active tab for restoration across page refresh
        AuthenticationSessionModel sess = context.getAuthenticationSession();
        String savedTab = sess.getAuthNote(SESSION_ACTIVE_TAB);
        formBuilder.setAttribute("activeTab", savedTab != null ? savedTab : "qr");

        // Pass masked phone number for display
        UserModel user = context.getUser();
        String rawPhone = user.getFirstAttribute(PHONE_ATTR);
        if (rawPhone != null) {
            formBuilder.setAttribute("phoneLast4", maskPhone(PhoneNumberValidator.toE164(rawPhone, resolveCountryCode(cfg))));
        }

        if (qrEnabled) {
            AuthenticationSessionModel session = context.getAuthenticationSession();
            String token = session.getAuthNote(SESSION_QR_TOKEN);
            if (token != null) {
                String businessPhone = cfg.get(CONFIG_BUSINESS_PHONE);
                String waMeLink = QrTokenService.buildWaMeLink(businessPhone, token);
                formBuilder.setAttribute("qrToken", token);
                formBuilder.setAttribute("qrWaMeLink", waMeLink);

                String realmName = context.getRealm().getName();
                formBuilder.setAttribute("qrStatusUrl",
                        "/realms/" + realmName + "/wa2fa/qr-status?token=" + token);
            }
        }

        return formBuilder.createForm(FTL);
    }

    // --- OTP send helper ---

    /**
     * Resend the existing (still-valid) OTP without generating a new one.
     */
    private void resendExistingOtp(AuthenticationFlowContext context, UserModel user, String phone) {
        String existingCode = otpService.getStoredOtp(context.getAuthenticationSession());
        if (existingCode == null) {
            // Fallback: generate new if somehow missing
            sendOtp(context, user, phone);
            return;
        }

        Map<String, String> cfg = configMap(context);
        String defLang = cfg.getOrDefault(CONFIG_DEFAULT_LANG, "en");
        String lang = LanguageResolver.resolve(user, defLang);

        MessageService ms = MessageService.fromConfig(cfg);
        ms.sendOtp(phone, lang, existingCode, realmDisplayName(context));
    }

    private boolean sendOtp(AuthenticationFlowContext context, UserModel user, String phone) {
        Map<String, String> cfg = configMap(context);

        int otpLen = configInt(context, CONFIG_OTP_LENGTH, 6);
        String defLang = cfg.getOrDefault(CONFIG_DEFAULT_LANG, "en");

        String code = otpService.generateOtp(otpLen);
        otpService.storeOtp(context.getAuthenticationSession(), code);

        String lang = LanguageResolver.resolve(user, defLang);

        MessageService ms = MessageService.fromConfig(cfg);
        MessageService.SendResult result = ms.sendOtp(phone, lang, code, realmDisplayName(context));

        // Log delivery result to Keycloak Events
        if (!result.isSuccess()) {
            context.getEvent()
                    .detail("wa2fa_channel", result.getChannel())
                    .detail("wa2fa_whatsapp_attempted", String.valueOf(result.isWhatsAppAttempted()))
                    .detail("wa2fa_sms_attempted", String.valueOf(result.isSmsAttempted()))
                    .detail("wa2fa_failure_reason", result.getFailureReason());
        } else {
            context.getEvent()
                    .detail("wa2fa_channel", result.getChannel())
                    .detail("wa2fa_phone", phone);
        }

        return result.isSuccess();
    }

    private Map<String, String> configMap(AuthenticationFlowContext context) {
        AuthenticatorConfigModel config = context.getAuthenticatorConfig();
        if (config == null || config.getConfig() == null) {
            return Map.of();
        }
        return config.getConfig();
    }

    private int configInt(AuthenticationFlowContext context, String key, int defaultValue) {
        String val = configMap(context).get(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private int getResendCount(AuthenticationFlowContext context) {
        String val = context.getAuthenticationSession().getAuthNote(SESSION_RESEND_COUNT);
        if (val == null) return 0;
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return 0; }
    }

    private void incrementResendCount(AuthenticationFlowContext context) {
        int count = getResendCount(context) + 1;
        context.getAuthenticationSession().setAuthNote(SESSION_RESEND_COUNT, String.valueOf(count));
    }

    /**
     * Get the realm display name (falls back to realm name if display name not set).
     */
    private static String realmDisplayName(AuthenticationFlowContext context) {
        RealmModel realm = context.getRealm();
        String displayName = realm.getDisplayName();
        return (displayName != null && !displayName.isBlank()) ? displayName : realm.getName();
    }

    /**
     * Resolve the default country code from authenticator config, falling back to env var.
     */
    private static String resolveCountryCode(Map<String, String> cfg) {
        String cc = cfg.get(CONFIG_DEFAULT_COUNTRY_CODE);
        if (cc != null && !cc.isBlank()) return cc.toUpperCase();
        String env = System.getenv("WA2FA_DEFAULT_COUNTRY_CODE");
        return (env != null && !env.isBlank()) ? env.toUpperCase() : null;
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return "****";
        return "******" + phone.substring(phone.length() - 4);
    }
}
