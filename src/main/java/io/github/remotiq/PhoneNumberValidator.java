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

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

/**
 * Phone number validation and formatting using Google's libphonenumber.
 *
 * Validates that the number is a real, dialable mobile or fixed-line number
 * and normalises it to E.164 format for the WhatsApp Cloud API.
 */
public class PhoneNumberValidator {

    private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();

    public record ValidationResult(boolean valid, String e164, String errorKey) {
        static ValidationResult ok(String e164) {
            return new ValidationResult(true, e164, null);
        }
        static ValidationResult fail(String errorKey) {
            return new ValidationResult(false, null, errorKey);
        }
    }

    /**
     * Validate and normalise a phone number.
     *
     * @param rawInput the user-entered phone number (e.g. "+91 98765 43210",
     *                 "00491234567890", "+1-555-123-4567")
     * @param defaultRegion ISO-3166 alpha-2 country code used when the input
     *                      does not contain an international prefix (e.g. "IN",
     *                      "US", "DE"). May be null — in that case the number
     *                      must start with "+".
     * @return ValidationResult with the E.164 formatted number on success,
     *         or an i18n error key on failure
     */
    public static ValidationResult validate(String rawInput, String defaultRegion) {
        if (rawInput == null || rawInput.isBlank()) {
            return ValidationResult.fail("wa2fa.phone.required");
        }

        String input = rawInput.trim();

        PhoneNumber parsed;
        try {
            parsed = UTIL.parse(input, defaultRegion);
        } catch (NumberParseException e) {
            return mapParseError(e);
        }

        // Must be a possible number (correct length for region)
        if (!UTIL.isPossibleNumber(parsed)) {
            PhoneNumberUtil.ValidationResult reason = UTIL.isPossibleNumberWithReason(parsed);
            return mapPossibilityError(reason);
        }

        // Must be a valid number (correct prefix for region)
        if (!UTIL.isValidNumber(parsed)) {
            return ValidationResult.fail("wa2fa.phone.notValid");
        }

        // Check number type — only mobile and fixed-line-or-mobile are accepted
        PhoneNumberUtil.PhoneNumberType type = UTIL.getNumberType(parsed);
        if (type != PhoneNumberUtil.PhoneNumberType.MOBILE
                && type != PhoneNumberUtil.PhoneNumberType.FIXED_LINE_OR_MOBILE) {
            return ValidationResult.fail("wa2fa.phone.notMobile");
        }

        // Format to E.164 (e.g. +919876543210)
        String e164 = UTIL.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164);
        return ValidationResult.ok(e164);
    }

    /**
     * Quick check — is this a valid mobile number?
     */
    public static boolean isValid(String rawInput, String defaultRegion) {
        return validate(rawInput, defaultRegion).valid();
    }

    /**
     * Parse + format to E.164. Returns null if invalid.
     */
    public static String toE164(String rawInput, String defaultRegion) {
        ValidationResult r = validate(rawInput, defaultRegion);
        return r.valid() ? r.e164() : null;
    }

    // ---- error mapping ----

    private static ValidationResult mapParseError(NumberParseException e) {
        return switch (e.getErrorType()) {
            case INVALID_COUNTRY_CODE -> ValidationResult.fail("wa2fa.phone.invalidCountryCode");
            case NOT_A_NUMBER         -> ValidationResult.fail("wa2fa.phone.notANumber");
            case TOO_SHORT_AFTER_IDD  -> ValidationResult.fail("wa2fa.phone.tooShort");
            case TOO_SHORT_NSN        -> ValidationResult.fail("wa2fa.phone.tooShort");
            case TOO_LONG             -> ValidationResult.fail("wa2fa.phone.tooLong");
            default                   -> ValidationResult.fail("wa2fa.phone.invalidFormat");
        };
    }

    private static ValidationResult mapPossibilityError(PhoneNumberUtil.ValidationResult reason) {
        return switch (reason) {
            case TOO_SHORT      -> ValidationResult.fail("wa2fa.phone.tooShort");
            case TOO_LONG       -> ValidationResult.fail("wa2fa.phone.tooLong");
            case INVALID_LENGTH -> ValidationResult.fail("wa2fa.phone.invalidLength");
            default             -> ValidationResult.fail("wa2fa.phone.invalidFormat");
        };
    }
}
