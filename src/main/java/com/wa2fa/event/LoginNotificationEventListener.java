package com.wa2fa.event;

import com.wa2fa.BrowserParser;
import com.wa2fa.LanguageResolver;
import com.wa2fa.MessageService;
import com.wa2fa.PhoneNumberValidator;
import com.wa2fa.Wa2faExecutor;
import com.wa2fa.action.PhoneVerificationRequiredActionFactory;
import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Event listener that handles:
 *
 * 1. Login notifications — sends WhatsApp/SMS alert when user logs in
 * 2. Phone change detection — when phoneNumber attribute changes (via user
 *    profile update or admin action), resets phoneNumberVerified to false
 *    and adds the wa2fa-verify-phone required action so the user must
 *    re-verify on next login.
 */
public class LoginNotificationEventListener implements EventListenerProvider {

    private static final Logger log = Logger.getLogger(LoginNotificationEventListener.class);
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final String PHONE_ATTR = "phoneNumber";
    private static final String PHONE_VERIFIED_ATTR = "phoneNumberVerified";

    private final KeycloakSession session;
    private final MessageService messageService;
    private final String defaultLanguage;
    private final String defaultCountryCode;
    private final boolean enabled;
    private final boolean async;

    public LoginNotificationEventListener(KeycloakSession session,
                                          MessageService messageService,
                                          String defaultLanguage,
                                          String defaultCountryCode,
                                          boolean enabled,
                                          boolean async) {
        this.session = session;
        this.messageService = messageService;
        this.defaultLanguage = defaultLanguage;
        this.defaultCountryCode = defaultCountryCode;
        this.enabled = enabled;
        this.async = async;
    }

    @Override
    public void onEvent(Event event) {
        // --- Login notification ---
        if (enabled && event.getType() == EventType.LOGIN) {
            handleLoginNotification(event);
        }

        // --- Phone change detection (user updates own profile) ---
        if (event.getType() == EventType.UPDATE_PROFILE) {
            handleUserProfileUpdate(event);
        }
    }

    @Override
    public void onEvent(AdminEvent adminEvent, boolean includeRepresentation) {
        // --- Phone change detection (admin updates user) ---
        if (adminEvent.getResourceType() == ResourceType.USER
                && adminEvent.getOperationType() == OperationType.UPDATE) {
            handleAdminUserUpdate(adminEvent);
        }
    }

    @Override
    public void close() {
        // no-op
    }

    // ---- Login Notification ----

    private void handleLoginNotification(Event event) {
        try {
            RealmModel realm = session.realms().getRealm(event.getRealmId());
            UserModel user = session.users().getUserById(realm, event.getUserId());
            if (user == null) return;

            String rawPhone = user.getFirstAttribute(PHONE_ATTR);
            if (rawPhone == null || rawPhone.isBlank()) return;

            // Validate and normalise phone with libphonenumber (use configured default country code)
            String phone = PhoneNumberValidator.toE164(rawPhone, defaultCountryCode);
            if (phone == null) {
                log.warnf("User %s has invalid phone number, skipping login notification", user.getUsername());
                return;
            }

            String username = user.getUsername();
            String timestamp = FMT.format(Instant.ofEpochMilli(event.getTime()));
            String ip = event.getIpAddress() != null ? event.getIpAddress() : "unknown";
            String lang = LanguageResolver.resolve(user, defaultLanguage);

            // Extract browser/device info from User-Agent header
            String userAgent = "unknown";
            try {
                jakarta.ws.rs.core.HttpHeaders headers = session.getContext().getRequestHeaders();
                if (headers != null) {
                    String ua = headers.getHeaderString("User-Agent");
                    if (ua != null && !ua.isBlank()) {
                        userAgent = BrowserParser.parse(ua);
                    }
                }
            } catch (Exception e) {
                log.debugf("Could not extract User-Agent: %s", e.getMessage());
            }

            final String browser = userAgent;
            // Get realm display name for message footer
            String realmName = realm.getDisplayName();
            if (realmName == null || realmName.isBlank()) realmName = realm.getName();
            final String realmDisplay = realmName;

            if (async) {
                Wa2faExecutor.submit(() -> send(phone, lang, username, timestamp, ip, browser, realmDisplay));
            } else {
                send(phone, lang, username, timestamp, ip, browser, realmDisplay);
            }
        } catch (Exception e) {
            log.error("Error sending login notification", e);
        }
    }

    // ---- Phone Change Detection (User Profile Update) ----

    private void handleUserProfileUpdate(Event event) {
        try {
            RealmModel realm = session.realms().getRealm(event.getRealmId());
            UserModel user = session.users().getUserById(realm, event.getUserId());
            if (user == null) return;

            checkAndInvalidatePhone(user);
        } catch (Exception e) {
            log.error("Error handling profile update for phone change", e);
        }
    }

    // ---- Phone Change Detection (Admin Update) ----

    private void handleAdminUserUpdate(AdminEvent adminEvent) {
        try {
            // Resource path is like "users/<userId>"
            String resourcePath = adminEvent.getResourcePath();
            if (resourcePath == null || !resourcePath.startsWith("users/")) return;

            // Ignore sub-resources like "users/<id>/role-mappings"
            String afterUsers = resourcePath.substring("users/".length());
            if (afterUsers.contains("/")) return;

            String userId = afterUsers;
            RealmModel realm = session.realms().getRealm(adminEvent.getRealmId());
            UserModel user = session.users().getUserById(realm, userId);
            if (user == null) return;

            checkAndInvalidatePhone(user);
        } catch (Exception e) {
            log.error("Error handling admin user update for phone change", e);
        }
    }

    // ---- Shared: Check if phone changed and invalidate verification ----

    /**
     * If the phone number has changed (i.e. it differs from what was verified),
     * reset phoneNumberVerified to false and add the verify-phone required action.
     *
     * Detection logic:
     * - If phoneNumberVerified is "true" but phone is null/empty → user cleared phone → reset
     * - If phoneNumberVerified is "true" → phone was verified before, any change triggers re-verify
     *   (Keycloak has already saved the new value by the time this listener runs)
     * - If phoneNumberVerified is not "true" → already unverified, nothing to do
     */
    private void checkAndInvalidatePhone(UserModel user) {
        String verified = user.getFirstAttribute(PHONE_VERIFIED_ATTR);

        // Only act if phone was previously verified
        if (!"true".equals(verified)) {
            return;
        }

        // Phone was verified — the update event means something changed on the user.
        // Since we can't easily compare old vs new phone value from the event,
        // we mark it as unverified. The required action's challenge will skip
        // if the phone hasn't actually changed (user re-verifies).
        // This is the safe approach — always re-verify after any profile update.
        user.setSingleAttribute(PHONE_VERIFIED_ATTR, "false");
        user.addRequiredAction(PhoneVerificationRequiredActionFactory.PROVIDER_ID);
        log.infof("Phone verification reset for user %s — re-verification required on next login",
                user.getUsername());
    }

    // ---- Send notification ----

    private void send(String phone, String lang, String username, String timestamp, String ip, String browser, String realmDisplayName) {
        try {
            MessageService.SendResult result = messageService.sendLoginNotification(phone, lang, username, timestamp, ip, browser, realmDisplayName);
            if (result.isSuccess()) {
                log.infof("Login notification sent for user %s via %s", username, result.getChannel());
            } else {
                log.errorf("Login notification failed for user %s: %s", username, result.getFailureReason());
            }
        } catch (Exception e) {
            log.error("Failed to send login notification", e);
        }
    }
}
