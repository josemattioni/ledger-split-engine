package com.github.josemattioni.ledger_split_engine.adapter.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The parameters of a request's query string, read from the query string and from nothing else.
 *
 * <p>{@code HttpServletRequest.getParameterMap()} would also parse a form-encoded body as parameters (and Tomcat would
 * read up to 2 MB of it to do so); here the body is never touched. Segments split on {@code &} (empty ones ignored),
 * {@code name=value} at the first {@code =}, {@code +} is a space, and percent-escapes are decoded as <b>strict</b>
 * UTF-8: an invalid sequence is an error, where {@code URLDecoder} would substitute a replacement character. The two
 * unreadable cases, a bad encoding and a parameter with no name, each get one fixed detail that repeats nothing sent.
 */
public final class QueryString {

    /** The detail of the 400 for a bad percent-encoding; a constant, never built from the request. */
    static final String MALFORMED = "the query string is not valid percent-encoded UTF-8";

    /** The detail of the 400 for {@code =1}: a parameter with a value and no name. */
    static final String NAMELESS = "the query string has a parameter with no name";

    private final Map<String, List<String>> parameters;

    private QueryString(Map<String, List<String>> parameters) {
        this.parameters = parameters;
    }

    /**
     * @throws InvalidRequestException (a 400) if the query string is not valid percent-encoded UTF-8 or has a
     *     parameter with no name
     */
    public static QueryString of(HttpServletRequest request) {
        return parse(request.getQueryString());
    }

    static QueryString parse(String raw) {
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        if (raw != null) {
            for (String segment : raw.split("&", -1)) {
                if (segment.isEmpty()) {
                    continue;
                }
                int equals = segment.indexOf('=');
                String rawName = equals < 0 ? segment : segment.substring(0, equals);
                String rawValue = equals < 0 ? "" : segment.substring(equals + 1);
                if (rawName.isEmpty()) {
                    throw new InvalidRequestException(NAMELESS);
                }
                parameters.computeIfAbsent(decode(rawName), name -> new ArrayList<>()).add(decode(rawValue));
            }
        }
        return new QueryString(parameters);
    }

    public Set<String> names() {
        return parameters.keySet();
    }

    /** Every value given for {@code name}, in order; empty when the parameter is absent. */
    public List<String> values(String name) {
        return parameters.getOrDefault(name, List.of());
    }

    private static String decode(String encoded) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            int c = encoded.codePointAt(i);
            if (c == '+') {
                bytes.write(' ');
            } else if (c == '%') {
                int high = i + 2 < encoded.length() ? Character.digit(encoded.charAt(i + 1), 16) : -1;
                int low = i + 2 < encoded.length() ? Character.digit(encoded.charAt(i + 2), 16) : -1;
                if (high < 0 || low < 0) {
                    throw new InvalidRequestException(MALFORMED);
                }
                bytes.write(high * 16 + low);
                i += 2;
            } else if (c < 0x80) {
                bytes.write(c);
            } else if (Character.isSurrogate((char) c) && c < 0x10000) {
                // An unpaired surrogate has no UTF-8 form. (Tomcat refuses raw non-ASCII in the request line before
                // the application sees it; this is for a container or a test that passes one through.)
                throw new InvalidRequestException(MALFORMED);
            } else {
                bytes.writeBytes(new String(Character.toChars(c)).getBytes(StandardCharsets.UTF_8));
                i += Character.charCount(c) - 1;
            }
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new InvalidRequestException(MALFORMED);
        }
    }
}
