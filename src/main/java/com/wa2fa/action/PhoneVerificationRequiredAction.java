package com.wa2fa.action;

import com.wa2fa.LanguageResolver;
import com.wa2fa.MessageService;
import com.wa2fa.OtpService;
import com.wa2fa.PhoneNumberValidator;
import com.wa2fa.Wa2faConfig;
import com.wa2fa.qr.QrTokenService;
import com.wa2fa.qr.VerificationStore;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * Required action that prompts users to verify their phone number
 * via WhatsApp OTP (with SMS fallback) and optionally via QR code scan.
 *
 * Behaviour:
 *   - If user already has a phoneNumber attribute (e.g. from registration),
 *     the phone is pre-filled and OTP is sent immediately on first render.
 *     The user sees the OTP screen with the phone shown (editable via "Change Number").
 *   - If user has no phone, the phone input screen is shown first.
 *   - On OTP screen, user can click "Change Number" to go back and enter a different number.
 *   - When QR is enabled, the OTP screen shows two methods:
 *     1. Scan QR Code — generates a wa.me deep link QR, user scans and sends message
 *     2. Receive OTP — current flow (WhatsApp/SMS OTP entry)
 *
 * Configuration is read from the WhatsApp OTP Authenticator's Admin Console
 * config (shared via Wa2faConfig), with environment variable fallback.
 */
public class PhoneVerificationRequiredAction implements RequiredActionProvider {

    private static final Logger log = Logger.getLogger(PhoneVerificationRequiredAction.class);

    private static final String FTL = "wa2fa-phone-verify.ftl";
    private static final String PHONE_ATTR = "phoneNumber";
    private static final String PHONE_VERIFIED_ATTR = "phoneNumberVerified";

    private static final String SESSION_PHONE = "wa2fa_pending_phone";
    private static final String SESSION_OTP_SENT = "wa2fa_otp_sent";
    private static final String SESSION_OTP_CODE_SENT = "wa2fa_otp_code_sent";
    private static final String SESSION_QR_TOKEN = "wa2fa_qr_token";

    private final OtpService otpService = new OtpService();

    @Override
    public void requiredActionChallenge(RequiredActionContext context) {
        // Skip if phone is already verified
        UserModel user = context.getUser();
        if ("true".equals(user.getFirstAttribute(PHONE_VERIFIED_ATTR))
                && user.getFirstAttribute(PHONE_ATTR) != null) {
            context.success();
            return;
        }

        // Check if user already has a phone number (e.g. from registration form or admin)
        String existingPhone = user.getFirstAttribute(PHONE_ATTR);
        if (existingPhone != null && !existingPhone.isBlank()) {
            // Validate the existing phone (use configured default country code)
            Wa2faConfig cfg = Wa2faConfig.resolve(context.getSession(), context.getRealm());
            PhoneNumberValidator.ValidationResult vr = PhoneNumberValidator.validate(existingPhone.trim(), cfg.defaultCountryCode());
            if (vr.valid()) {
                String phone = vr.e164();
                AuthenticationSessionModel session = context.getAuthenticationSession();
                session.setAuthNote(SESSION_PHONE, phone);

                boolean otpEnabled = cfg.otpEnabled();

                if (cfg.qrEnabled() && !otpEnabled) {
                    // QR-only mode: generate QR token, no OTP
                    session.setAuthNote(SESSION_OTP_SENT, "true");
                    generateQrTokenIfEnabled(context, session, phone);
                    log.infof("QR-only mode: generated QR for phone %s user %s", phone, user.getUsername());
                    context.challenge(buildForm(context, null, true, phone));
                    return;
                }

                if (cfg.qrEnabled()) {
                    // Dual mode (QR + OTP): generate QR token, don't auto-send OTP
                    session.setAuthNote(SESSION_OTP_SENT, "true");
                    generateQrTokenIfEnabled(context, session, phone);
                    log.infof("Dual mode: generated QR for phone %s user %s", phone, user.getUsername());
                    context.challenge(buildForm(context, null, true, phone));
                    return;
                }

                if (otpEnabled) {
                    // OTP-only mode: auto-send OTP immediately
                    boolean sent = sendOtp(context, session, phone);
                    if (sent) {
                        session.setAuthNote(SESSION_OTP_SENT, "true");
                        session.setAuthNote(SESSION_OTP_CODE_SENT, "true");
                        log.infof("Auto-sent OTP to existing phone %s for user %s", phone, user.getUsername());

                        generateQrTokenIfEnabled(context, session, phone);
                        context.challenge(buildForm(context, "wa2fa.otp.sent", true, phone));
                        return;
                    }
                    context.challenge(buildForm(context, "wa2fa.otp.sendFailed", false, phone));
                    return;
                }

                // Both disabled — misconfiguration
                log.warn("Both OTP and QR disabled — cannot verify phone");
                context.challenge(buildForm(context, null, false, phone));
                return;
            }
        }

        // No valid phone — show phone input screen
        context.challenge(buildForm(context, null, false, existingPhone));
    }

    @Override
    public void processAction(RequiredActionContext context) {
        MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();

        // Persist active tab selection across form submissions
        String activeTab = form.getFirst("activeTab");
        if (activeTab != null && !activeTab.isBlank()) {
            context.getAuthenticationSession().setAuthNote("wa2fa_active_tab", activeTab);
        }

        // "Change Number" — go back to phone input screen
        if (form.containsKey("changeNumber")) {
            AuthenticationSessionModel session = context.getAuthenticationSession();
            session.removeAuthNote(SESSION_OTP_SENT);
            session.removeAuthNote(SESSION_OTP_CODE_SENT);
            cleanupQrToken(session);
            String currentPhone = session.getAuthNote(SESSION_PHONE);
            context.challenge(buildForm(context, null, false, currentPhone));
            return;
        }

        // QR code verified (submitted by JavaScript polling)
        if (form.containsKey("qrVerified")) {
            handleQrVerified(context);
            return;
        }

        // Handle "Send OTP" from OTP tab (lazy send when QR is default)
        if (form.containsKey("sendOtpTab")) {
            Wa2faConfig otpCheck = Wa2faConfig.resolve(context.getSession(), context.getRealm());
            if (!otpCheck.otpEnabled()) {
                log.warn("OTP send requested but OTP is disabled (phone verify)");
                String ph = context.getAuthenticationSession().getAuthNote(SESSION_PHONE);
                context.challenge(buildForm(context, null, true, ph));
                return;
            }
            AuthenticationSessionModel session = context.getAuthenticationSession();
            String phone = session.getAuthNote(SESSION_PHONE);
            if (phone != null) {
                Wa2faConfig cfg = Wa2faConfig.resolve(context.getSession(), context.getRealm());
                // Only generate new OTP if none exists or existing one expired
                if (!otpService.isOtpStillValid(session, cfg.otpExpiry())) {
                    boolean sent = sendOtp(context, session, phone);
                    if (!sent) {
                        context.challenge(buildForm(context, "wa2fa.otp.sendFailed", true, phone));
                        return;
                    }
                } else {
                    log.infof("OTP still valid, resending same code via sendOtpTab for phone %s", phone);
                    resendExistingOtp(context, session, phone);
                }
                session.setAuthNote(SESSION_OTP_SENT, "true");
                session.setAuthNote(SESSION_OTP_CODE_SENT, "true");
                generateQrTokenIfEnabled(context, session, phone);
                context.challenge(buildForm(context, "wa2fa.otp.sent", true, phone));
            } else {
                context.challenge(buildForm(context, null, false, null));
            }
            return;
        }

        // Phase 2 resend: user clicked "Resend Code" on the OTP/verification screen
        // Uses the phone already stored in session (no phoneNumber form field in Phase 2)
        if (form.containsKey("resendOtp")) {
            handleResendOtp(context);
            return;
        }

        // Phase 1: user submitted phone number → send OTP
        if (form.containsKey("sendOtp")) {
            handleSendOtp(context, form);
            return;
        }

        // Phase 2: user submitted OTP → validate
        handleValidateOtp(context, form);
    }

    @Override
    public void evaluateTriggers(RequiredActionContext context) {
        UserModel user = context.getUser();
        String phone = user.getFirstAttribute(PHONE_ATTR);
        String verified = user.getFirstAttribute(PHONE_VERIFIED_ATTR);
        String verifiedPhone = user.getFirstAttribute("phoneNumberVerifiedValue");

        boolean needsVerification = false;

        if (phone == null || phone.isBlank()) {
            needsVerification = true;
        } else if (!"true".equals(verified)) {
            needsVerification = true;
        } else if (verifiedPhone != null && !phone.equals(verifiedPhone)) {
            user.setSingleAttribute(PHONE_VERIFIED_ATTR, "false");
            needsVerification = true;
        }

        if (needsVerification) {
            user.addRequiredAction(PhoneVerificationRequiredActionFactory.PROVIDER_ID);
        }
    }

    @Override
    public void close() {
        // no-op
    }

    // --- Phase 1: Send OTP ---

    private void handleSendOtp(RequiredActionContext context, MultivaluedMap<String, String> form) {
        String rawPhone = form.getFirst("phoneNumber");

        if (rawPhone == null || rawPhone.isBlank()) {
            context.challenge(buildForm(context, "wa2fa.phone.required", false, null));
            return;
        }

        // Validate with libphonenumber (use configured default country code)
        Wa2faConfig cfg = Wa2faConfig.resolve(context.getSession(), context.getRealm());
        PhoneNumberValidator.ValidationResult vr = PhoneNumberValidator.validate(rawPhone.trim(), cfg.defaultCountryCode());
        if (!vr.valid()) {
            context.challenge(buildForm(context, vr.errorKey(), false, rawPhone));
            return;
        }

        String phone = vr.e164();
        AuthenticationSessionModel session = context.getAuthenticationSession();
        session.setAuthNote(SESSION_PHONE, phone);

        boolean otpEnabled = cfg.otpEnabled();

        if (cfg.qrEnabled() && !otpEnabled) {
            // QR-only mode: generate QR, no OTP
            session.setAuthNote(SESSION_OTP_SENT, "true");
            generateQrTokenIfEnabled(context, session, phone);
            context.challenge(buildForm(context, null, true, phone));
            return;
        }

        if (cfg.qrEnabled()) {
            // Dual mode: generate QR, don't auto-send OTP
            session.setAuthNote(SESSION_OTP_SENT, "true");
            generateQrTokenIfEnabled(context, session, phone);
            context.challenge(buildForm(context, null, true, phone));
            return;
        }

        if (!otpEnabled) {
            log.warn("Both OTP and QR disabled — cannot verify phone (handleSendOtp)");
            context.challenge(buildForm(context, null, false, phone));
            return;
        }

        boolean sent = sendOtp(context, session, phone);
        if (!sent) {
            context.challenge(buildForm(context, "wa2fa.otp.sendFailed", false, phone));
            return;
        }

        session.setAuthNote(SESSION_OTP_SENT, "true");
        session.setAuthNote(SESSION_OTP_CODE_SENT, "true");
        generateQrTokenIfEnabled(context, session, phone);
        context.challenge(buildForm(context, "wa2fa.otp.sent", true, phone));
    }

    // --- Phase 2: Resend OTP (uses phone from session, not from form) ---

    private void handleResendOtp(RequiredActionContext context) {
        AuthenticationSessionModel session = context.getAuthenticationSession();
        String phone = session.getAuthNote(SESSION_PHONE);

        if (phone == null || phone.isBlank()) {
            // No phone in session — fall back to phone input screen
            log.warn("Resend OTP requested but no phone in session — showing phone input");
            context.challenge(buildForm(context, null, false, null));
            return;
        }

        Wa2faConfig cfg = Wa2faConfig.resolve(context.getSession(), context.getRealm());
        if (!cfg.otpEnabled()) {
            log.warn("OTP resend requested but OTP is disabled");
            context.challenge(buildForm(context, null, true, phone));
            return;
        }

        // Resend existing OTP if still valid, otherwise generate a new one
        if (otpService.isOtpStillValid(session, cfg.otpExpiry())) {
            log.infof("OTP still valid, resending same code for phone %s", phone);
            resendExistingOtp(context, session, phone);
        } else {
            boolean sent = sendOtp(context, session, phone);
            if (!sent) {
                context.challenge(buildForm(context, "wa2fa.otp.sendFailed", true, phone));
                return;
            }
        }

        session.setAuthNote(SESSION_OTP_SENT, "true");
        session.setAuthNote(SESSION_OTP_CODE_SENT, "true");
        generateQrTokenIfEnabled(context, session, phone);
        context.challenge(buildForm(context, "wa2fa.otp.sent", true, phone));
    }

    // --- Phase 2: Validate OTP ---

    private void handleValidateOtp(RequiredActionContext context, MultivaluedMap<String, String> form) {
        String otp = form.getFirst("otp");
        AuthenticationSessionModel session = context.getAuthenticationSession();
        String phone = session.getAuthNote(SESSION_PHONE);

        if (otp == null || otp.isBlank()) {
            context.challenge(buildForm(context, "wa2fa.otp.missing", true, phone));
            return;
        }

        Wa2faConfig cfg = Wa2faConfig.resolve(context.getSession(), context.getRealm());
        int expiry = cfg.otpExpiry();
        if (!otpService.validateOtp(session, otp.trim(), expiry)) {
            context.challenge(buildForm(context, "wa2fa.otp.invalid", true, phone));
            return;
        }

        completeVerification(context, phone);
    }

    // --- QR Code Verified ---

    private void handleQrVerified(RequiredActionContext context) {
        AuthenticationSessionModel session = context.getAuthenticationSession();
        String token = session.getAuthNote(SESSION_QR_TOKEN);
        String phone = session.getAuthNote(SESSION_PHONE);

        if (token == null) {
            log.warn("QR verified submitted but no token in session");
            context.challenge(buildForm(context, "wa2fa.otp.invalid", true, phone));
            return;
        }

        VerificationStore.PendingVerification pv = VerificationStore.getInstance().getByToken(token);
        if (pv == null || !pv.isVerified()) {
            log.warnf("QR verification not confirmed for token %s", token);
            context.challenge(buildForm(context, "wa2fa.qr.notVerified", true, phone));
            return;
        }

        // Use the phone from the QR verification (matches expected phone)
        completeVerification(context, phone);
    }

    // --- Complete verification ---

    private void completeVerification(RequiredActionContext context, String phone) {
        UserModel user = context.getUser();
        user.setSingleAttribute(PHONE_ATTR, phone);
        user.setSingleAttribute(PHONE_VERIFIED_ATTR, "true");
        user.setSingleAttribute("phoneNumberVerifiedValue", phone);

        // Remove the required action so it doesn't trigger again
        user.removeRequiredAction(PhoneVerificationRequiredActionFactory.PROVIDER_ID);

        // Cleanup
        AuthenticationSessionModel session = context.getAuthenticationSession();
        session.removeAuthNote(SESSION_PHONE);
        session.removeAuthNote(SESSION_OTP_SENT);
        session.removeAuthNote(SESSION_OTP_CODE_SENT);
        cleanupQrToken(session);

        log.infof("Phone %s verified for user %s", phone, user.getUsername());
        context.success();
    }

    // --- QR helpers ---

    private void generateQrTokenIfEnabled(RequiredActionContext context,
                                           AuthenticationSessionModel session, String phone) {
        Wa2faConfig cfg = Wa2faConfig.resolve(context.getSession(), context.getRealm());
        if (!cfg.qrEnabled()) return;

        String businessPhone = cfg.businessPhone();
        if (businessPhone == null || businessPhone.isBlank()) {
            log.warn("QR enabled but no business phone configured — skipping QR");
            return;
        }

        // Reuse existing token if still valid (prevents regeneration on page refresh)
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
        String waMeLink = QrTokenService.buildWaMeLink(businessPhone, token);

        // Store in VerificationStore for webhook matching
        String sessionId = session.getParentSession().getId();
        int expiry = cfg.otpExpiry();
        VerificationStore.getInstance().createPending(token, phone, expiry, sessionId);

        // Store token in auth session for form rendering
        session.setAuthNote(SESSION_QR_TOKEN, token);

        log.infof("Generated QR token %s for phone %s, wa.me link: %s", token, phone, waMeLink);
    }

    private void cleanupQrToken(AuthenticationSessionModel session) {
        String token = session.getAuthNote(SESSION_QR_TOKEN);
        if (token != null) {
            String sessionId = session.getParentSession().getId();
            VerificationStore.getInstance().remove(sessionId);
            session.removeAuthNote(SESSION_QR_TOKEN);
        }
    }

    // --- OTP send helper ---

    /**
     * Resend the existing (still-valid) OTP without generating a new one.
     */
    private void resendExistingOtp(RequiredActionContext context, AuthenticationSessionModel session, String phone) {
        String existingCode = otpService.getStoredOtp(session);
        if (existingCode == null) {
            sendOtp(context, session, phone);
            return;
        }

        Wa2faConfig cfg = Wa2faConfig.resolve(context.getSession(), context.getRealm());
        String lang = LanguageResolver.resolve(context.getUser(), cfg.defaultLanguage());
        MessageService ms = cfg.buildMessageService();
        ms.sendOtp(phone, lang, existingCode, realmDisplayName(context));
    }

    private boolean sendOtp(RequiredActionContext context, AuthenticationSessionModel session, String phone) {
        Wa2faConfig cfg = Wa2faConfig.resolve(context.getSession(), context.getRealm());

        String code = otpService.generateOtp(cfg.otpLength());
        otpService.storeOtp(session, code);

        String lang = LanguageResolver.resolve(context.getUser(), cfg.defaultLanguage());
        MessageService ms = cfg.buildMessageService();
        MessageService.SendResult result = ms.sendOtp(phone, lang, code, realmDisplayName(context));

        // Log delivery result to Keycloak Events
        if (!result.isSuccess()) {
            context.getEvent()
                    .detail("wa2fa_channel", result.getChannel())
                    .detail("wa2fa_whatsapp_attempted", String.valueOf(result.isWhatsAppAttempted()))
                    .detail("wa2fa_sms_attempted", String.valueOf(result.isSmsAttempted()))
                    .detail("wa2fa_failure_reason", result.getFailureReason())
                    .error("wa2fa_otp_send_failed");
            log.errorf("Failed to send OTP to %s: %s", phone, result.getFailureReason());
        } else {
            context.getEvent()
                    .detail("wa2fa_channel", result.getChannel())
                    .detail("wa2fa_phone", phone);
        }

        return result.isSuccess();
    }

    /**
     * Get the realm display name (falls back to realm name if display name not set).
     */
    private static String realmDisplayName(RequiredActionContext context) {
        String displayName = context.getRealm().getDisplayName();
        return (displayName != null && !displayName.isBlank()) ? displayName : context.getRealm().getName();
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return "****";
        return "******" + phone.substring(phone.length() - 4);
    }

    // --- Form builder ---

    private Response buildForm(RequiredActionContext context, String messageKey, boolean otpSent, String phone) {
        var formBuilder = context.form();
        if (messageKey != null) {
            if (otpSent && "wa2fa.otp.sent".equals(messageKey)) {
                formBuilder.setInfo(messageKey);
            } else {
                formBuilder.setError(messageKey);
            }
        }
        formBuilder.setAttribute("otpSent", otpSent);

        // Restore active tab across page refreshes
        String savedTab = context.getAuthenticationSession().getAuthNote("wa2fa_active_tab");
        formBuilder.setAttribute("activeTab", savedTab != null ? savedTab : "qr");

        // Track whether OTP was actually sent (distinct from otpSent which means "phase 2 active")
        boolean otpCodeSent = "true".equals(
                context.getAuthenticationSession().getAuthNote(SESSION_OTP_CODE_SENT));
        formBuilder.setAttribute("otpCodeSent", otpCodeSent);

        if (phone != null) {
            formBuilder.setAttribute("phoneNumber", phone);
            formBuilder.setAttribute("phoneLast4", maskPhone(phone));
        }

        // QR code attributes
        Wa2faConfig cfg = Wa2faConfig.resolve(context.getSession(), context.getRealm());
        boolean qrEnabled = cfg.qrEnabled();
        boolean otpEnabledFlag = cfg.otpEnabled();
        formBuilder.setAttribute("qrEnabled", qrEnabled);
        formBuilder.setAttribute("otpEnabled", otpEnabledFlag);

        if (qrEnabled && otpSent) {
            AuthenticationSessionModel session = context.getAuthenticationSession();
            String token = session.getAuthNote(SESSION_QR_TOKEN);
            if (token != null) {
                String businessPhone = cfg.businessPhone();
                String waMeLink = QrTokenService.buildWaMeLink(businessPhone, token);
                formBuilder.setAttribute("qrToken", token);
                formBuilder.setAttribute("qrWaMeLink", waMeLink);

                // Build the status polling URL
                // The realm name is available from context
                String realmName = context.getRealm().getName();
                formBuilder.setAttribute("qrStatusUrl",
                        "/realms/" + realmName + "/wa2fa/qr-status?token=" + token);
            }
        }

        return formBuilder.createForm(FTL);
    }
}
