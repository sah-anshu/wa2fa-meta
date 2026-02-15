package com.wa2fa.qr;

import com.wa2fa.Wa2faConfig;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.resource.RealmResourceProvider;

/**
 * Keycloak RealmResourceProvider that exposes the wa2fa REST endpoints
 * under /realms/{realm}/wa2fa/...
 *
 * Endpoints:
 *   GET  /realms/{realm}/wa2fa/webhook      — WhatsApp webhook verification
 *   POST /realms/{realm}/wa2fa/webhook      — Incoming WhatsApp messages
 *   GET  /realms/{realm}/wa2fa/qr-status    — Poll QR verification status
 *
 * Configuration is resolved from the realm's authenticator config (Admin Console)
 * with fallback to environment variables.
 */
public class Wa2faResourceProvider implements RealmResourceProvider {

    private final KeycloakSession session;

    public Wa2faResourceProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public Object getResource() {
        // Resolve config from Admin Console / env vars at request time
        RealmModel realm = session.getContext().getRealm();
        Wa2faConfig cfg = Wa2faConfig.resolve(session, realm);

        String webhookVerifyToken = cfg.webhookVerifyToken();
        String appSecret = cfg.appSecret();

        return new Wa2faWebhookResource(session, webhookVerifyToken, appSecret);
    }

    @Override
    public void close() {
        // no-op
    }
}
