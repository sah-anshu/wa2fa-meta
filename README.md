# wa2fa — WhatsApp 2FA for Keycloak (Meta Cloud API)

`wa2fa` is a Keycloak 26.x provider bundle that adds WhatsApp-based authentication features using the Meta WhatsApp Cloud API.

## What this plugin provides

1. WhatsApp OTP as a login step (`Authenticator`)
2. Phone verification flow (`Required Action`)
3. Login alerts on successful sign-in (`Event Listener`)
4. Optional QR scan verification with webhook callback (`Realm Resource Provider`)
5. Optional HTTP SMS fallback when WhatsApp delivery fails

Supported UI/message languages: `en`, `hi`, `es`, `fr`, `de`, `ar`, `pt`.

## Scope in v1.1

Version `1.1` is Meta-only.

## Compatibility

- Keycloak: `26.x` (Quarkus distribution)
- Java: `17+`
- Maven: `3.9+`

## Quick Start (Docker)

1. Copy and edit env file:

```bash
cp .env.example .env
```

2. Start stack:

```bash
docker compose up --build
```

3. Open Keycloak:

- URL: `http://localhost:8080`
- Admin user: `admin`
- Admin password: `admin`

## Build and Install Manually

1. Build JAR:

```bash
mvn clean package
```

2. Copy provider:

```bash
cp target/wa2fa-1.1.jar /opt/keycloak/providers/
```

3. Rebuild Keycloak and start:

```bash
/opt/keycloak/bin/kc.sh build
/opt/keycloak/bin/kc.sh start
```

## Registered Keycloak Providers

- `AuthenticatorFactory`: `WhatsApp OTP (wa2fa)`
- `RequiredActionFactory`: `Verify Phone Number via WhatsApp (wa2fa)`
- `EventListenerProviderFactory`: `wa2fa-login-notification`
- `RealmResourceProviderFactory`: `wa2fa`

Runtime service registrations are in:

- `src/main/resources/META-INF/services/org.keycloak.authentication.AuthenticatorFactory`
- `src/main/resources/META-INF/services/org.keycloak.authentication.RequiredActionFactory`
- `src/main/resources/META-INF/services/org.keycloak.events.EventListenerProviderFactory`
- `src/main/resources/META-INF/services/org.keycloak.services.resource.RealmResourceProviderFactory`

## Meta Setup Requirements

You need these from Meta Business / WhatsApp Manager:

- Access Token
- Phone Number ID
- Approved template for OTP (default: `otp_message`)
- Approved template for login alerts (default: `login_notification`)

### Template parameters expected

- OTP template: OTP code parameter
- Login notification template: `username`, `timestamp`, `ip`, `browser`

## Keycloak Configuration

### 1) Browser Flow (OTP)

1. Go to `Authentication -> Flows`
2. Duplicate `Browser` flow (recommended)
3. Add execution: `WhatsApp OTP (wa2fa)`
4. Set requirement to `REQUIRED`
5. Configure authenticator settings

Recommended structure:

```text
Browser flow
  Username Password Form (REQUIRED)
  OTP sub-flow (REQUIRED)
    WhatsApp OTP (wa2fa) (REQUIRED)
```

Do not keep a `Condition - user configured` gate in this OTP sub-flow if you want WhatsApp OTP consistently enforced.

### 2) Required Action (phone verification)

1. Go to `Authentication -> Required Actions`
2. Enable: `Verify Phone Number via WhatsApp (wa2fa)`

This flow writes/uses these user attributes:

- `phoneNumber`
- `phoneNumberVerified`
- `phoneNumberVerifiedValue`

### 3) Event Listener (login alert)

1. Go to `Realm Settings -> Events`
2. Add listener: `wa2fa-login-notification`

## Authenticator Config Fields

Configured in the execution settings for `WhatsApp OTP (wa2fa)`:

- `wa2fa.accessToken`
- `wa2fa.phoneNumberId`
- `wa2fa.apiVersion`
- `wa2fa.otpLength`
- `wa2fa.otpExpiry`
- `wa2fa.templateOtp`
- `wa2fa.templateLogin`
- `wa2fa.templateLoginLayout`
- `wa2fa.defaultLanguage`
- `wa2fa.defaultCountryCode`
- `wa2fa.smsFallbackUrl`
- `wa2fa.smsFallbackMethod`
- `wa2fa.qrEnabled`
- `wa2fa.otpEnabled`
- `wa2fa.businessPhone`
- `wa2fa.webhookVerifyToken`
- `wa2fa.appSecret`
- `wa2fa.maxResend`
- `wa2fa.qrAckVerified`
- `wa2fa.qrAckMismatch`
- `wa2fa.qrAckExpired`
- `wa2fa.qrAckNoMatch`

## QR Verification Endpoints

Exposed under the realm resource provider `wa2fa`:

- `GET /realms/{realm}/wa2fa/webhook` (Meta webhook verification)
- `POST /realms/{realm}/wa2fa/webhook` (incoming message callback)
- `GET /realms/{realm}/wa2fa/qr-status?token=...` (polling endpoint)

If `WA2FA_APP_SECRET` / `wa2fa.appSecret` is set, webhook payload signature (`X-Hub-Signature-256`) is verified.

## Environment Variables

Core env vars (also available in `.env.example`):

- `WA2FA_ACCESS_TOKEN`
- `WA2FA_PHONE_NUMBER_ID`
- `WA2FA_API_VERSION`
- `WA2FA_TEMPLATE_OTP`
- `WA2FA_TEMPLATE_LOGIN`
- `WA2FA_DEFAULT_LANGUAGE`
- `WA2FA_DEFAULT_COUNTRY_CODE`
- `WA2FA_OTP_EXPIRY`
- `WA2FA_LOGIN_NOTIFICATION_ENABLED`
- `WA2FA_LOGIN_NOTIFICATION_ASYNC`
- `WA2FA_SMS_FALLBACK_URL`
- `WA2FA_SMS_FALLBACK_METHOD`
- `WA2FA_QR_ENABLED`
- `WA2FA_BUSINESS_PHONE`
- `WA2FA_WEBHOOK_VERIFY_TOKEN`
- `WA2FA_APP_SECRET`

## Notes on Fallback and Delivery

- Delivery is attempted via WhatsApp first.
- If configured, SMS fallback is attempted on WhatsApp failure.
- Event details include channel/failure metadata for auditing.

## Project Layout

```text
src/main/java/com/wa2fa/
  action/               # Required action (phone verification)
  authenticator/        # WhatsApp OTP authenticator
  event/                # Login notification listener
  qr/                   # Webhook + QR status resource
  ...shared services/utilities

src/main/resources/
  META-INF/services/    # SPI registrations
  theme-resources/      # FTL templates + i18n bundles + QR JS
```

## Troubleshooting

1. Provider not visible in Keycloak:
- Ensure JAR is in `/opt/keycloak/providers/`
- Run `/opt/keycloak/bin/kc.sh build`
- Restart Keycloak

2. OTP not sent:
- Verify token/phone-id values
- Verify template exists and is approved
- Check Keycloak logs for WhatsApp API errors

3. QR flow not completing:
- Ensure webhook URL points to `/realms/{realm}/wa2fa/webhook`
- Ensure verify token matches
- Set `WA2FA_APP_SECRET` for signed webhook validation

4. Phone rejected:
- Number is validated with libphonenumber
- Use E.164 (`+...`) or set `WA2FA_DEFAULT_COUNTRY_CODE`

## Development

Compile only:

```bash
mvn -DskipTests compile
```

Package:

```bash
mvn clean package
```
