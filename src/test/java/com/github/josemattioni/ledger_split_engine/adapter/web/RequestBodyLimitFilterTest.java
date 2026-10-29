package com.github.josemattioni.ledger_split_engine.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The body limit in isolation, with a limit of 10 bytes so the bodies stay small: what counts is the bytes actually
 * read, so the same limit holds when the length was declared and when it was not (a chunked body).
 */
class RequestBodyLimitFilterTest {

    private static final long LIMIT = 10;
    private static final String API_PATH = "/api/v1/accounts";

    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter(LIMIT);

    private static MockHttpServletRequest request(String path, int bodyBytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContent(new byte[bodyBytes]);
        return request;
    }

    /** A request that does not declare its length, as a chunked one does not: the body is still all there. */
    private static MockHttpServletRequest chunkedRequest(int bodyBytes) {
        return chunkedRequestTo(API_PATH, bodyBytes);
    }

    private static MockHttpServletRequest chunkedRequestTo(String path, int bodyBytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(new byte[bodyBytes]);
        return request;
    }

    private HttpServletRequest seenByTheHandler(MockHttpServletRequest request) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return (HttpServletRequest) chain.getRequest();
    }

    // ------------------------------------------------------------------ the limit itself

    @Test
    void aBodyOfExactlyTheLimitIsReadInFull() throws Exception {
        InputStream body = seenByTheHandler(request(API_PATH, 10)).getInputStream();

        assertThat(body.readAllBytes()).hasSize(10);
    }

    @Test
    void oneByteOverTheLimitFailsTheRead() throws Exception {
        InputStream body = seenByTheHandler(chunkedRequest(11)).getInputStream();

        assertThatThrownBy(body::readAllBytes)
                .isInstanceOf(RequestBodyTooLargeException.class)
                .hasMessage("the request body is larger than 10 bytes");
    }

    @Test
    void aSingleByteReadCountsToo() throws Exception {
        InputStream body = seenByTheHandler(chunkedRequest(11)).getInputStream();
        for (int i = 0; i < 10; i++) {
            assertThat(body.read()).isEqualTo(0);
        }

        assertThatThrownBy(body::read).isInstanceOf(RequestBodyTooLargeException.class);
    }

    @Test
    void aHugeBodyIsStoppedAtTheLimitNotReadToTheEnd() throws Exception {
        MockHttpServletRequest original = chunkedRequest(1_000_000);
        InputStream body = seenByTheHandler(original).getInputStream();

        assertThatThrownBy(() -> {
            byte[] buffer = new byte[4096];
            while (body.read(buffer) >= 0) {
                // keep reading until the limit stops us
            }
        }).isInstanceOf(RequestBodyTooLargeException.class);

        // Not a byte more than one buffer was taken from the underlying stream.
        assertThat(original.getInputStream().available()).isGreaterThan(1_000_000 - 4096 - 1);
    }

    @Test
    void aDeclaredLengthOverTheLimitFailsOnTheFirstReadWithoutConsumingAnything() throws Exception {
        MockHttpServletRequest original = request(API_PATH, 11);
        InputStream body = seenByTheHandler(original).getInputStream();

        assertThatThrownBy(body::read).isInstanceOf(RequestBodyTooLargeException.class);
        assertThat(original.getInputStream().available()).isEqualTo(11);
    }

    @Test
    void theReaderIsLimitedToo() throws Exception {
        MockHttpServletRequest original = chunkedRequest(11);
        original.setCharacterEncoding(StandardCharsets.UTF_8);
        BufferedReader reader = seenByTheHandler(original).getReader();

        assertThatThrownBy(() -> reader.lines().count()).isInstanceOf(java.io.UncheckedIOException.class)
                .hasCauseInstanceOf(RequestBodyTooLargeException.class);
    }

    @Test
    void aRequestThatNeverReadsItsBodyIsNotAffectedByHowLargeItIs() throws Exception {
        MockHttpServletRequest huge = request(API_PATH, 1_000_000);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(huge, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    // ------------------------------------------------------------------ where it applies

    // No path decides it: a spelling of an API path that a gate on the raw URI would miss is wrapped like any other.
    @ParameterizedTest
    @ValueSource(strings = {
        API_PATH,
        "/api;x=1/v1/accounts",
        "/api/%761/accounts",
        "/%61pi/v1/accounts",
        "//api/v1/accounts",
        "/somewhere/else",
        "/",
    })
    void everyRequestIsWrappedWhateverItsPath(String path) throws Exception {
        MockHttpServletRequest original = request(path, 1);

        assertThat(seenByTheHandler(original)).isNotSameAs(original);
    }

    @Test
    void aBodyPastTheLimitFailsTheReadOnAnyPath() throws Exception {
        InputStream body = seenByTheHandler(chunkedRequestTo("/api;x=1/v1/accounts", 11)).getInputStream();

        assertThatThrownBy(body::readAllBytes).isInstanceOf(RequestBodyTooLargeException.class);
    }

    // ------------------------------------------------------------------ an exception that escapes the chain

    private MockHttpServletResponse filterThrowing(Throwable thrown, MockHttpServletResponse response) throws Exception {
        FilterChain chain = (req, res) -> {
            if (thrown instanceof IOException io) {
                throw io;
            }
            throw (ServletException) thrown;
        };
        filter.doFilter(request(API_PATH, 1), response, chain);
        return response;
    }

    // The form-content filter reads the body outside the DispatcherServlet, so ApiExceptionHandler never sees this one.
    @Test
    void anOversizedBodyExceptionThatEscapesTheChainIsAnsweredWith413() throws Exception {
        MockHttpServletResponse response = filterThrowing(new RequestBodyTooLargeException(LIMIT), new MockHttpServletResponse());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString())
                .isEqualTo("{\"detail\":\"the request body is larger than 10 bytes\",\"status\":413,\"title\":\"Content Too Large\"}");
    }

    @Test
    void theSameExceptionWrappedInAServletExceptionIsFoundInTheCauseChain() throws Exception {
        MockHttpServletResponse response = filterThrowing(
                new ServletException("wrapped", new RequestBodyTooLargeException(LIMIT)), new MockHttpServletResponse());

        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void anyOtherIoFailureIsRethrownNotTurnedInto413() {
        assertThatThrownBy(() -> filterThrowing(new IOException("boom"), new MockHttpServletResponse()))
                .isInstanceOf(IOException.class)
                .hasMessage("boom");
    }

    @Test
    void anAnswerAlreadyCommittedIsNotOverwritten() {
        MockHttpServletResponse committed = new MockHttpServletResponse();
        committed.setCommitted(true);

        assertThatThrownBy(() -> filterThrowing(new RequestBodyTooLargeException(LIMIT), committed))
                .isInstanceOf(RequestBodyTooLargeException.class);
    }
}
