package com.wa2fa;

import org.keycloak.sessions.AuthenticationSessionModel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;

public class OtpService {

    private static final String OTP_CODE_KEY = "wa2fa_otp_code";
    private static final String OTP_TIMESTAMP_KEY = "wa2fa_otp_timestamp";
    private static final String OTP_ATTEMPTS_KEY = "wa2fa_otp_attempts";
    private static final String OTP_LOCKOUT_KEY = "wa2fa_otp_lockout";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Default maximum OTP verification attempts before lockout */
    public static final int DEFAULT_MAX_ATTEMPTS = 5;
    /** Lockout duration in seconds after max attempts exceeded */
    public static final int LOCKOUT_DURATION_SECONDS = 300; // 5 minutes

    /**
     * Generate a numeric OTP of the given length (4-10 digits).
     */
    public String generateOtp(int length) {
        if (length < 4 || length > 10) {
            length = 6;
        }
        int min = (int) Math.pow(10, length - 1);
        int max = (int) Math.pow(10, length) - 1;
        int otp = RANDOM.nextInt(max - min + 1) + min;
        return String.valueOf(otp);
    }

    /**
     * Store OTP and timestamp in the authentication session.
     */
    public void storeOtp(AuthenticationSessionModel session, String otpCode) {
        session.setAuthNote(OTP_CODE_KEY, otpCode);
        session.setAuthNote(OTP_TIMESTAMP_KEY, String.valueOf(Instant.now().getEpochSecond()));
    }

    /**
     * Validate the user-entered OTP against the stored value.
     * Returns true if the OTP matches and has not expired.
     * Enforces brute-force protection with max attempts and lockout.
     */
    public boolean validateOtp(AuthenticationSessionModel session, String userInput, int expirySeconds) {
        return validateOtp(session, userInput, expirySeconds, DEFAULT_MAX_ATTEMPTS);
    }

    /**
     * Validate the user-entered OTP against the stored value.
     * Returns true if the OTP matches and has not expired.
     * Enforces brute-force protection: after maxAttempts failed tries, the OTP
     * is invalidated and the session is locked out for LOCKOUT_DURATION_SECONDS.
     *
     * @param session       authentication session
     * @param userInput     the OTP entered by the user
     * @param expirySeconds OTP validity window in seconds
     * @param maxAttempts   maximum allowed verification attempts (0 = unlimited)
     * @return true if valid, false if invalid/expired/locked out
     */
    public boolean validateOtp(AuthenticationSessionModel session, String userInput,
                               int expirySeconds, int maxAttempts) {
        // Check lockout
        if (isLockedOut(session)) {
            return false;
        }

        String storedOtp = session.getAuthNote(OTP_CODE_KEY);
        String timestampStr = session.getAuthNote(OTP_TIMESTAMP_KEY);

        if (storedOtp == null || timestampStr == null) {
            return false;
        }

        // Check expiry
        long storedTime = Long.parseLong(timestampStr);
        if (Instant.now().getEpochSecond() - storedTime > expirySeconds) {
            clearOtp(session);
            return false;
        }

        // Constant-time comparison to prevent timing attacks.
        // An attacker cannot determine correct digits by measuring response time.
        boolean valid = constantTimeEquals(storedOtp, userInput);
        if (valid) {
            clearOtp(session);
            clearAttempts(session);
        } else {
            // Track failed attempt
            int attempts = incrementAttempts(session);
            if (maxAttempts > 0 && attempts >= maxAttempts) {
                // Max attempts exceeded — invalidate OTP and lock out
                clearOtp(session);
                setLockout(session);
            }
        }
        return valid;
    }

    /**
     * Check if the session is currently locked out due to too many failed attempts.
     */
    public boolean isLockedOut(AuthenticationSessionModel session) {
        String lockoutStr = session.getAuthNote(OTP_LOCKOUT_KEY);
        if (lockoutStr == null) return false;
        long lockoutTime = Long.parseLong(lockoutStr);
        if (Instant.now().getEpochSecond() - lockoutTime > LOCKOUT_DURATION_SECONDS) {
            // Lockout expired — clear it
            session.removeAuthNote(OTP_LOCKOUT_KEY);
            clearAttempts(session);
            return false;
        }
        return true;
    }

    /**
     * Get the number of failed OTP attempts in this session.
     */
    public int getAttemptCount(AuthenticationSessionModel session) {
        String val = session.getAuthNote(OTP_ATTEMPTS_KEY);
        if (val == null) return 0;
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return 0; }
    }

    private int incrementAttempts(AuthenticationSessionModel session) {
        int count = getAttemptCount(session) + 1;
        session.setAuthNote(OTP_ATTEMPTS_KEY, String.valueOf(count));
        return count;
    }

    private void clearAttempts(AuthenticationSessionModel session) {
        session.removeAuthNote(OTP_ATTEMPTS_KEY);
    }

    private void setLockout(AuthenticationSessionModel session) {
        session.setAuthNote(OTP_LOCKOUT_KEY, String.valueOf(Instant.now().getEpochSecond()));
    }

    /**
     * Constant-time string comparison to prevent timing side-channel attacks.
     * Uses MessageDigest.isEqual which is guaranteed constant-time in the JDK.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }

    /**
     * Check if an OTP is currently stored and still valid (not expired).
     * Useful for "Resend" — only regenerate if the existing OTP has expired.
     */
    public boolean isOtpStillValid(AuthenticationSessionModel session, int expirySeconds) {
        String storedOtp = session.getAuthNote(OTP_CODE_KEY);
        String timestampStr = session.getAuthNote(OTP_TIMESTAMP_KEY);

        if (storedOtp == null || timestampStr == null) {
            return false;
        }

        long storedTime = Long.parseLong(timestampStr);
        return Instant.now().getEpochSecond() - storedTime <= expirySeconds;
    }

    /**
     * Get the currently stored OTP code (or null if none).
     */
    public String getStoredOtp(AuthenticationSessionModel session) {
        return session.getAuthNote(OTP_CODE_KEY);
    }

    /**
     * Remove OTP data from the session.
     */
    public void clearOtp(AuthenticationSessionModel session) {
        session.removeAuthNote(OTP_CODE_KEY);
        session.removeAuthNote(OTP_TIMESTAMP_KEY);
    }
}
