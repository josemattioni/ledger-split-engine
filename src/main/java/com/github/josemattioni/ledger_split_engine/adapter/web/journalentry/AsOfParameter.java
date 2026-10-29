package com.github.josemattioni.ledger_split_engine.adapter.web.journalentry;

import com.github.josemattioni.ledger_split_engine.adapter.web.QueryString;
import jakarta.servlet.http.HttpServletRequest;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads {@code as_of} from the query string of a balance request: a strict RFC 3339 instant. Every failure is an
 * {@link IllegalArgumentException}, so a controller that calls this inside {@code InvalidRequestException.guard}
 * answers 400 with the message as the detail. Unknown parameters and malformed percent-encoding are judged earlier
 * (see {@code QueryParameterInterceptor}); the value is read from {@link QueryString}, never the container's map.
 *
 * <p>The trap: <b>the JDK's ISO parser is more lenient than RFC 3339</b> (case-insensitive {@code T} and {@code Z},
 * optional seconds, nine fractional digits, offsets with seconds). The pattern below decides the shape; the parse
 * that follows only rejects impossible values (month 13, hour 25, an offset beyond &plusmn;18:00).
 */
final class AsOfParameter {

    static final String NAME = "as_of";

    private static final Pattern RFC_3339 =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,6})?(Z|[+-]\\d{2}:\\d{2})$");

    private AsOfParameter() {}

    /**
     * @return the instant, or {@code null} when {@code as_of} is absent (a current balance)
     * @throws IllegalArgumentException for anything but a single well-formed {@code as_of}, naming what is wrong
     */
    static Instant from(HttpServletRequest request) {
        List<String> values = QueryString.of(request).values(NAME);
        if (values.isEmpty()) {
            return null;
        }
        if (values.size() != 1) {
            throw new IllegalArgumentException(NAME + " must be given at most once");
        }
        String value = values.get(0);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(NAME + " must not be empty");
        }
        if (!RFC_3339.matcher(value).matches()) {
            throw malformed();
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeException e) {
            throw malformed();
        }
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException(NAME + " must be an RFC 3339 date-time with an explicit UTC offset,"
                + " for example 2026-10-21T15:00:00Z or 2026-10-21T12:00:00-03:00 (seconds are required, the fraction"
                + " has at most 6 digits, T and Z are uppercase)");
    }
}
