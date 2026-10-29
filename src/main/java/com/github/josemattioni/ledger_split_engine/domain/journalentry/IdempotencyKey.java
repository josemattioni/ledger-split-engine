package com.github.josemattioni.ledger_split_engine.domain.journalentry;

import com.github.josemattioni.ledger_split_engine.domain.EchoedValue;
import java.util.regex.Pattern;

/**
 * The shape of an {@code Idempotency-Key}: 1 to {@value #MAX_LENGTH} visible (non-whitespace,
 * printable) ASCII characters. The one Java source of that rule.
 *
 * <p>It is a class of its own so a controller can check the key <em>before</em> any use case runs: a
 * use case that builds a {@link JournalEntry} would otherwise meet a malformed key as an
 * {@link IllegalArgumentException}, which outside request-to-domain construction is a 500, not the
 * 400 the client has earned. {@link JournalEntry} still checks it, as the domain's own guard.
 */
public final class IdempotencyKey {

    public static final int MAX_LENGTH = 255;

    private static final Pattern PATTERN = Pattern.compile("^[!-~]{1," + MAX_LENGTH + "}$");

    private IdempotencyKey() {}

    public static boolean isWellFormed(String key) {
        return key != null && PATTERN.matcher(key).matches();
    }

    /**
     * @throws IllegalArgumentException if the key is not well-formed (the message echoes the key)
     */
    public static void requireWellFormed(String key) {
        if (!isWellFormed(key)) {
            throw new IllegalArgumentException("idempotency_key must be 1-" + MAX_LENGTH
                    + " visible ASCII characters (no whitespace): " + EchoedValue.quoted(key));
        }
    }
}
