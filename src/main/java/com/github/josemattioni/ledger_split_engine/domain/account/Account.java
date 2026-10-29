package com.github.josemattioni.ledger_split_engine.domain.account;

import com.github.josemattioni.ledger_split_engine.domain.EchoedValue;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record Account(
        String code,
        String ownerRef,
        AccountType type,
        Currency currency,
        Instant createdAt) {

    private static final int MAX_OWNER_REF_LENGTH = 100;

    /**
     * Whitespace at either end, in the Unicode sense ({@code White_Space}: no-break space, em space, ideographic
     * space and the rest). {@code String.trim()} only strips characters up to U+0020, so a value padded with an
     * invisible space of any other kind would pass for unpadded.
     */
    private static final Pattern PADDED = Pattern.compile("^\\s|\\s\\z", Pattern.UNICODE_CHARACTER_CLASS);

    public Account {
        Objects.requireNonNull(code, "code must not be null");
        if (!AccountCode.isWellFormed(code)) {
            throw new IllegalArgumentException("code must be " + AccountCode.SHAPE + ": " + EchoedValue.quoted(code));
        }
        if (ownerRef != null
                && (ownerRef.isEmpty()
                        || PADDED.matcher(ownerRef).find()
                        || ownerRef.codePointCount(0, ownerRef.length()) > MAX_OWNER_REF_LENGTH)) {
            throw new IllegalArgumentException(
                    "owner_ref must be either omitted/null or non-blank with no leading/trailing whitespace and at most "
                            + MAX_OWNER_REF_LENGTH + " characters: " + EchoedValue.quoted(ownerRef));
        }
        if (ownerRef != null && !isStorableText(ownerRef)) {
            throw new IllegalArgumentException(
                    "owner_ref must not contain a NUL character or an unpaired surrogate: the value could not be stored as sent");
        }
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
    }

    /**
     * Whether the database can keep this text exactly as given. PostgreSQL text cannot hold U+0000 (the insert fails,
     * which would be a 500 for a client's mistake), and the driver replaces an unpaired surrogate, which has no UTF-8
     * form, with {@code ?} (the account would be stored with a value nobody sent). A value the system cannot store is
     * refused, not stored altered.
     */
    private static boolean isStorableText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 0) {
                return false;
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }
}
