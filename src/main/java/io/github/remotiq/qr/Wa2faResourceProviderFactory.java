/*
 * Copyright (c) 2026-present RemotiQ (Anshu S.)
 * SPDX-License-Identifier: MIT
 *
 * This file is part of wa2fa-meta (https://github.com/RemotiQ/wa2fa-meta).
 * Do not remove this header.
 * The copyright and permission notice must be included in all copies
 * or substantial portions of the Software. See LICENSE.
 */

package io.github.remotiq.qr;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resource.RealmResourceProviderFactory;

/**
 * Factory for the wa2fa RealmResourceProvider.
 *
 * Registers the REST endpoints under /realms/{realm}/wa2fa/...
 *
 * Configuration (webhook verify token, app secret) is resolved per-request
 * from the realm's authenticator config (Admin Console) with fallback to
 * environment variables (WA2FA_WEBHOOK_VERIFY_TOKEN, WA2FA_APP_SECRET).
 */
public class Wa2faResourceProviderFactory implements RealmResourceProviderFactory {

    private static final Logger log = Logger.getLogger(Wa2faResourceProviderFactory.class);

    public static final String PROVIDER_ID = "wa2fa";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public Wa2faResourceProvider create(KeycloakSession session) {
        return new Wa2faResourceProvider(session);
    }

    @Override
    public void init(Config.Scope config) {
        log.info("wa2fa REST resource provider initialized");
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
