package com.github.josemattioni.ledger_split_engine.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The body limit through Tomcat: the cases MockMvc cannot show. A body that declares no length (chunked) is counted
 * as it is read; and the request path is decoded by Tomcat and Spring before a handler is chosen, so every spelling
 * of an API path that reaches a handler (a {@code ;} path parameter, a percent-encoded character) must get the same
 * limit as the plain one. A limit decided on the raw URI left those spellings, and the form-content filter's
 * unbounded read of a {@code PUT}/{@code PATCH}/{@code DELETE} form body, outside it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestBodyLimitSocketTest extends AbstractIntegrationTest {

    private static final int LIMIT = 262_144;
    private static final String DETAIL = "the request body is larger than 262144 bytes";
    private static final int CHUNK = 8192;

    @LocalServerPort
    private int port;

    private record Reply(int status, String contentType, String body) {}

    /** {@code {} followed by spaces to {@code size} bytes, never closed: whitespace keeps the JSON reader reading. */
    private static byte[] unfinishedJson(int size) {
        byte[] body = new byte[size];
        Arrays.fill(body, (byte) ' ');
        body[0] = '{';
        return body;
    }

    /** One request over a raw socket, the body sent either with its length or as 8 KiB chunks. */
    private Reply send(String method, String target, String contentType, byte[] body, boolean chunked) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(15_000);
            StringBuilder head = new StringBuilder(method + " " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n");
            head.append("Idempotency-Key: socket-").append(UUID.randomUUID()).append("\r\n");
            if (contentType != null) {
                head.append("Content-Type: ").append(contentType).append("\r\n");
            }
            head.append(chunked ? "Transfer-Encoding: chunked\r\n" : "Content-Length: " + body.length + "\r\n").append("\r\n");
            OutputStream out = socket.getOutputStream();
            try {
                out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
                if (chunked) {
                    for (int from = 0; from < body.length; from += CHUNK) {
                        byte[] part = Arrays.copyOfRange(body, from, Math.min(body.length, from + CHUNK));
                        out.write((Integer.toHexString(part.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                        out.write(part);
                        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    }
                    out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                } else {
                    out.write(body);
                }
                out.flush();
            } catch (IOException serverAnsweredAndClosedEarly) {
                // The server may answer as soon as the limit is hit; the answer is still read below.
            }
            return read(socket);
        }
    }

    private static Reply read(Socket socket) throws IOException {
        ByteArrayOutputStream in = new ByteArrayOutputStream();
        socket.getInputStream().transferTo(in);
        String raw = in.toString(StandardCharsets.ISO_8859_1);
        int separator = raw.indexOf("\r\n\r\n");
        String[] head = raw.substring(0, separator).split("\r\n");
        String contentType = "";
        boolean chunked = false;
        for (String header : head) {
            String lower = header.toLowerCase();
            if (lower.startsWith("content-type:")) contentType = header.substring(13).trim();
            if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) chunked = true;
        }
        String body = raw.substring(separator + 4);
        return new Reply(
                Integer.parseInt(head[0].split(" ")[1]),
                contentType,
                new String((chunked ? dechunk(body) : body).getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
    }

    private static String dechunk(String chunked) {
        StringBuilder out = new StringBuilder();
        int position = 0;
        while (position < chunked.length()) {
            int eol = chunked.indexOf("\r\n", position);
            int size = Integer.parseInt(chunked.substring(position, eol).trim(), 16);
            if (size == 0) {
                break;
            }
            out.append(chunked, eol + 2, eol + 2 + size);
            position = eol + 2 + size + 2;
        }
        return out.toString();
    }

    private void assertTooLarge(Reply reply) {
        assertThat(reply.status()).as("status (body: %s)", reply.body()).isEqualTo(413);
        assertThat(reply.contentType()).startsWith("application/problem+json");
        assertThat((String) JsonPath.read(reply.body(), "$.detail")).isEqualTo(DETAIL);
    }

    // ------------------------------------------------------------------ the plain path, declared and chunked

    @Test
    void aBodyWithADeclaredLengthPastTheLimitIs413() throws IOException {
        assertTooLarge(send("POST", "/api/v1/accounts", "application/json", unfinishedJson(LIMIT + 1), false));
    }

    @Test
    void aChunkedBodyThatGoesPastTheLimitIs413WithNoLengthEverDeclared() throws IOException {
        assertTooLarge(send("POST", "/api/v1/accounts", "application/json", unfinishedJson(LIMIT + 1), true));
    }

    @Test
    void aChunkedBodyWithinTheLimitIsAccepted() throws IOException {
        // 32 KiB: chunked requests work, they are only counted.
        String account = "{\"code\":\"" + uniqueCode("chunked_ok") + "\",\"type\":\"ASSET\"" + " ".repeat(32_768) + "}";

        Reply reply = send("POST", "/api/v1/accounts", "application/json", account.getBytes(StandardCharsets.UTF_8), true);

        assertThat(reply.status()).as("status (body: %s)", reply.body()).isEqualTo(201);
    }

    // ------------------------------------------------------------------ every spelling of the path

    /**
     * Spellings of {@code /api/v1/accounts} that Spring still routes to the handler: Tomcat decodes the percent-encoded
     * characters and drops the {@code ;} parameters. A spelling that no handler answers is a 404 that never reads the
     * body, so there is nothing for a limit to bypass; the test says which of the two it saw. Observed: the
     * {@code ;}, {@code %76}, {@code %61} spellings reach the handler (and get the 413); {@code //api/v1/accounts} is
     * a 404 (Spring does not route an empty first segment), so its body is never read.
     */
    private void assertLimitedWhereverItRoutes(String spelling, boolean chunked) throws IOException {
        Reply small = send("POST", spelling, "application/json", "{}".getBytes(StandardCharsets.UTF_8), false);
        Reply big = send("POST", spelling, "application/json", unfinishedJson(LIMIT + 1), chunked);

        if (small.status() == 404) {
            assertThat(big.status()).as("%s is not routed, so its body is never read", spelling).isEqualTo(404);
        } else {
            assertThat(small.status()).as("%s is routed to the handler (body: %s)", spelling, small.body()).isEqualTo(400);
            assertTooLarge(big);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api;x=1/v1/accounts",
        "/api/v1;a=b/accounts",
        "/api/v1/accounts;jsessionid=1",
        "/api/%761/accounts",
        "/%61pi/v1/accounts",
        "/api/v1/%61ccounts",
        "//api/v1/accounts",
        "/api//v1/accounts",
    })
    void everySpellingOfThePathThatReachesAHandlerGetsTheLimitWithADeclaredLength(String spelling) throws IOException {
        assertLimitedWhereverItRoutes(spelling, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api;x=1/v1/accounts", "/api/%761/accounts", "/%61pi/v1/accounts", "//api/v1/accounts"})
    void everySpellingOfThePathThatReachesAHandlerGetsTheLimitForAChunkedBodyToo(String spelling) throws IOException {
        assertLimitedWhereverItRoutes(spelling, true);
    }

    @Test
    void theReversalPathWithAParameterGetsTheLimit() throws IOException {
        String path = "/api;x/v1/journal-entries/" + UUID.randomUUID() + "/reversal";

        assertTooLarge(send("POST", path, null, new byte[LIMIT + 1], false));
    }

    // A reversal has no body, and one byte is enough to say so: a chunked body is judged by its first byte (a 400), not
    // read up to the limit, while a body that declares itself past the limit still fails on that first read (a 413).
    @Test
    void aChunkedBodyOnAReversalIsRefusedAsABodyNotReadUpToTheLimit() throws IOException {
        String path = "/api/v1/journal-entries/" + UUID.randomUUID() + "/reversal";

        Reply reply = send("POST", path, "application/json", unfinishedJson(LIMIT + 1), true);

        assertThat(reply.status()).as("status (body: %s)", reply.body()).isEqualTo(400);
        assertThat((String) JsonPath.read(reply.body(), "$.detail"))
                .isEqualTo("a reversal request must not have a body: the server builds the reversal's lines");
    }

    @Test
    void aSmallChunkedBodyOnAReversalIsTheSame400() throws IOException {
        String path = "/api/v1/journal-entries/" + UUID.randomUUID() + "/reversal";

        Reply reply = send("POST", path, "application/json", "{}".getBytes(StandardCharsets.UTF_8), true);

        assertThat(reply.status()).as("status (body: %s)", reply.body()).isEqualTo(400);
    }

    @Test
    void aReversalWithNoBodyAtAllIsNotMistakenForOneWithABody() throws IOException {
        String path = "/api/v1/journal-entries/" + UUID.randomUUID() + "/reversal";

        Reply reply = send("POST", path, null, new byte[0], false);

        // Past the body check: the entry does not exist.
        assertThat(reply.status()).as("status (body: %s)", reply.body()).isEqualTo(404);
    }

    @Test
    void theEntryPathWithAPercentEncodedCharacterGetsTheLimit() throws IOException {
        assertTooLarge(send("POST", "/api/v%31/journal-entries", "application/json", unfinishedJson(LIMIT + 1), false));
        assertTooLarge(send("POST", "/%61pi/v1/journal-entries", "application/json", unfinishedJson(LIMIT + 1), false));
    }

    // ------------------------------------------------------------------ the form-content filter's read

    /**
     * Spring Boot's form-content filter reads and parses a {@code PUT}, {@code PATCH} or {@code DELETE} body that is
     * form-encoded, in full, before the request reaches the handler that answers 405. The limit must have been applied
     * by then.
     */
    @ParameterizedTest
    @ValueSource(strings = {"DELETE", "PUT", "PATCH"})
    void aFormEncodedBodyOnAMethodNoEndpointHasIsStillLimitedBeforeItIsParsed(String method) throws IOException {
        byte[] form = new byte[1_048_576];
        Arrays.fill(form, (byte) 'b');
        form[0] = 'a';
        form[1] = '=';

        assertTooLarge(send(method, "/api/v1/accounts/some_account", "application/x-www-form-urlencoded", form, false));
    }
}
