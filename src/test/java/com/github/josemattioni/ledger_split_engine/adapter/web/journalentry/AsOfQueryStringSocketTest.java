package com.github.josemattioni.ledger_split_engine.adapter.web.journalentry;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.josemattioni.ledger_split_engine.AbstractIntegrationTest;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountType;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The query string of the API's endpoints through Tomcat's real decoder, over a raw socket: {@code MockMvc} decodes
 * query parameters itself and never runs it, and {@code java.net.http} refuses to send some of these. A malformed
 * percent-encoding makes Tomcat throw when the parameters are read, which would be a generic 500; the application
 * maps it to a 400 with one fixed detail that repeats nothing the client sent. A literal {@code +} is different:
 * it decodes to a space, so the application sees a malformed {@code as_of} and says so by name.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AsOfQueryStringSocketTest extends AbstractIntegrationTest {

    private static final String FIXED_DETAIL = "the query string is not valid percent-encoded UTF-8";

    @LocalServerPort
    private int port;

    private String account;

    private record Reply(int status, String contentType, String body) {}

    @BeforeEach
    void registerAnAccount() {
        account = registerAccount("seller_payable", AccountType.LIABILITY);
    }

    private Reply get(String queryString) throws IOException {
        return exchange("GET", "/api/v1/accounts/" + account + "/balance?" + queryString, null, null);
    }

    private Reply exchange(String method, String target, String requestType, String requestBody) throws IOException {
        String request = method + " " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                + (requestType == null ? "" : "Content-Type: " + requestType + "\r\n")
                + "Content-Length: " + (requestBody == null ? 0 : requestBody.getBytes(StandardCharsets.UTF_8).length)
                + "\r\n\r\n"
                + (requestBody == null ? "" : requestBody);
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
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

    // ------------------------------------------------------------------ a malformed percent-encoding: 400, one fixed detail

    @ParameterizedTest(name = "[{index}] ?{0}")
    @ValueSource(strings = {"as_of=%zz", "as_of=%", "as_of=%E9", "x=%zz"})
    void aMalformedPercentEncodingIsA400WithTheOneFixedDetailNotA500(String queryString) throws IOException {
        Reply reply = get(queryString);

        assertThat(reply.status()).as("status (body: %s)", reply.body()).isEqualTo(400);
        assertThat(reply.contentType()).startsWith("application/problem+json");
        assertThat((String) JsonPath.read(reply.body(), "$.detail")).isEqualTo(FIXED_DETAIL);
        assertThat(reply.body())
                .as("nothing the client sent, and nothing of Tomcat's own message, is repeated")
                .doesNotContain("%zz")
                .doesNotContain("%E9")
                .doesNotContain("Character decoding")
                .doesNotContain("InvalidParameter");
    }

    // ------------------------------------------------------------------ the same rule on the other endpoints

    private static final String ENTRY_ID = "11111111-1111-1111-1111-111111111111";

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "GET /api/v1/accounts/ACCOUNT?x=%zz",
        "POST /api/v1/accounts?x=%zz",
        "POST /api/v1/journal-entries?x=%zz",
        "POST /api/v1/journal-entries/" + ENTRY_ID + "/reversal?x=%zz"})
    void aMalformedPercentEncodingOnAnyEndpointIsTheSameFixed400(String endpoint) throws IOException {
        String[] parts = endpoint.replace("ACCOUNT", account).split(" ");
        boolean hasBody = !parts[0].equals("GET") && !parts[1].contains("/reversal");

        Reply reply = exchange(
                parts[0],
                parts[1],
                hasBody ? "application/json" : null,
                hasBody ? "{\"code\":\"x\",\"type\":\"ASSET\"}" : null);

        assertThat(reply.status()).as("status (body: %s)", reply.body()).isEqualTo(400);
        assertThat(reply.contentType()).startsWith("application/problem+json");
        assertThat((String) JsonPath.read(reply.body(), "$.detail")).isEqualTo(FIXED_DETAIL);
    }

    @Test
    void aStrayParameterIsRefusedOnAnAccountLookupOverTheWire() throws IOException {
        Reply reply = exchange("GET", "/api/v1/accounts/" + account + "?x=1", null, null);

        assertThat(reply.status()).as("status (body: %s)", reply.body()).isEqualTo(400);
        assertThat(reply.contentType()).startsWith("application/problem+json");
        assertThat((String) JsonPath.read(reply.body(), "$.detail")).contains("\"x\"");
    }

    /**
     * Tomcat parses a form-encoded POST body as parameters, so judging {@code getParameterMap()} on a request with no
     * query string would turn this into a 400 about a parameter named {@code code}. The wrong content type is the
     * client's mistake, and is reported as it always was.
     */
    @Test
    void aFormEncodedPostWithoutAQueryStringIsStillRefusedForItsContentTypeNotItsFields() throws IOException {
        Reply reply = exchange("POST", "/api/v1/accounts", "application/x-www-form-urlencoded", "code=x&type=ASSET");

        assertThat(reply.status()).as("status (body: %s)", reply.body()).isEqualTo(415);
        assertThat(reply.body()).doesNotContain("query parameter");
    }

    // ------------------------------------------------------------------ only the query string is a query string

    private static final String UNKNOWN_X = "unknown query parameter(s): \"x\"; this endpoint accepts no query parameters";
    private static final String FORM = "application/x-www-form-urlencoded";

    /**
     * The container's own parameter map would also parse a form-encoded body when the request has a query string:
     * {@code POST /accounts?x=1} with {@code code=a&type=ASSET} used to be refused for three "query parameters",
     * {@code code}, {@code type} and {@code x}. Only the query string counts.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "/api/v1/accounts?x=1",
        "/api/v1/journal-entries?x=1",
        "/api/v1/journal-entries/11111111-1111-1111-1111-111111111111/reversal?x=1",
    })
    void theFieldsOfAFormEncodedBodyAreNotQueryParameters(String target) throws IOException {
        Reply reply = exchange("POST", target, FORM, "code=a&type=ASSET");

        assertThat(reply.status()).as("status (body: %s)", reply.body()).isEqualTo(400);
        assertThat(reply.contentType()).startsWith("application/problem+json");
        assertThat((String) JsonPath.read(reply.body(), "$.detail")).isEqualTo(UNKNOWN_X);
    }

    @Test
    void aLargeFormBodyNextToAQueryStringIsNotReadAtAll() throws IOException {
        // 1 MB of form fields: the container would parse every one of them to build its parameter map.
        String form = "a=" + "b".repeat(1_000_000);

        Reply reply = exchange("POST", "/api/v1/accounts?x=1", FORM, form);

        assertThat(reply.status()).as("status (body: %s)", reply.body()).isEqualTo(400);
        assertThat((String) JsonPath.read(reply.body(), "$.detail")).isEqualTo(UNKNOWN_X);
    }

    @Test
    void aParameterWithNoNameOverTheWireIsItsOwnFixedDetail() throws IOException {
        Reply reply = get("=1");

        assertThat(reply.status()).as("status (body: %s)", reply.body()).isEqualTo(400);
        assertThat(reply.contentType()).startsWith("application/problem+json");
        assertThat((String) JsonPath.read(reply.body(), "$.detail")).isEqualTo("the query string has a parameter with no name");
    }

    // ------------------------------------------------------------------ plus and %2B

    @Test
    void aLiteralPlusOffsetDecodesToASpaceAndIsA400NamingAsOf() throws IOException {
        Reply reply = get("as_of=2020-01-01T00:00:00+00:00");

        assertThat(reply.status()).as("status (body: %s)", reply.body()).isEqualTo(400);
        assertThat(reply.contentType()).startsWith("application/problem+json");
        assertThat((String) JsonPath.read(reply.body(), "$.detail")).contains("as_of").isNotEqualTo(FIXED_DETAIL);
    }

    @Test
    void thePositiveOffsetPercentEncodedAsPercent2BIsAccepted() throws IOException {
        Reply reply = get("as_of=2020-01-01T00:00:00%2B00:00");

        assertThat(reply.status()).as("status (body: %s)", reply.body()).isEqualTo(200);
        assertThat((String) JsonPath.read(reply.body(), "$.as_of")).isEqualTo("2020-01-01T00:00:00Z");
        assertThat((Integer) JsonPath.read(reply.body(), "$.balance_cents")).isZero();
    }

    @Test
    void theNegativeOffsetPercentEncodedAsPercent2DIsAccepted() throws IOException {
        Reply reply = get("as_of=2019-12-31T21:00:00%2D03:00");

        assertThat(reply.status()).as("status (body: %s)", reply.body()).isEqualTo(200);
        assertThat((String) JsonPath.read(reply.body(), "$.as_of")).isEqualTo("2020-01-01T00:00:00Z");
    }
}
