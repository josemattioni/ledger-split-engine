package com.github.josemattioni.ledger_split_engine.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.method.HandlerMethod;

/**
 * The rule every endpoint shares: a query parameter the handler does not declare is a 400 that names it, before the
 * handler runs. The request is a mock because only the interceptor's decisions are under test here; the names come
 * from the raw query string alone ({@link QueryString}), never from the container's parameter map, which the mock
 * would fail to answer and which the tests verify is never asked.
 */
class QueryParameterInterceptorTest {

    @SuppressWarnings("unused")
    static class Handlers {
        void takesNothing() {}

        @AcceptedQueryParameters("as_of")
        void takesAsOf() {}

        @AcceptedQueryParameters({"status", "page"})
        void takesTwo() {}
    }

    private final QueryParameterInterceptor interceptor = new QueryParameterInterceptor();
    private final HttpServletResponse response = mock(HttpServletResponse.class);

    private static HandlerMethod handler(String name) throws Exception {
        Method method = Handlers.class.getDeclaredMethod(name);
        return new HandlerMethod(new Handlers(), method);
    }

    /** A request whose raw query string is exactly {@code raw}. */
    private static HttpServletRequest requestWithQuery(String raw) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getQueryString()).thenReturn(raw);
        return request;
    }

    /** A request whose query string carries these names and values, percent-encoded as a client would. */
    private static HttpServletRequest requestWith(String... namesAndValues) {
        List<String> segments = new ArrayList<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            segments.add(URLEncoder.encode(namesAndValues[i], StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(namesAndValues[i + 1], StandardCharsets.UTF_8));
        }
        return requestWithQuery(String.join("&", segments));
    }

    private String rejectionOf(HttpServletRequest request, String handlerName) throws Exception {
        HandlerMethod handler = handler(handlerName);
        Throwable thrown = catchThrowable(() -> interceptor.preHandle(request, response, handler));
        assertThat(thrown).isExactlyInstanceOf(InvalidRequestException.class);
        verify(request, never()).getParameterMap();
        return thrown.getMessage();
    }

    // ------------------------------------------------------------------ nothing to judge

    @Test
    void aRequestWithoutAQueryStringIsAcceptedAndNothingIsRead() throws Exception {
        HttpServletRequest request = requestWithQuery(null);

        assertThat(interceptor.preHandle(request, response, handler("takesNothing"))).isTrue();

        verify(request, never()).getParameterMap();
        verify(request, never()).getInputStream();
    }

    @Test
    void aHandlerThatIsNotAControllerMethodIsLeftAlone() {
        HttpServletRequest request = requestWithQuery("x=1");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();

        verify(request, never()).getParameterMap();
    }

    @Test
    void anEmptyQueryStringIsAcceptedBecauseItHasNoParameters() throws Exception {
        assertThat(interceptor.preHandle(requestWithQuery(""), response, handler("takesNothing"))).isTrue();
        assertThat(interceptor.preHandle(requestWithQuery("&&"), response, handler("takesNothing"))).isTrue();
    }

    // The container's parameter map would parse a form-encoded POST body too; the query string is all that is read.
    @Test
    void theContainersParameterMapIsNeverAskedNorTheBodyRead() throws Exception {
        HttpServletRequest request = requestWithQuery("as_of=2026-10-21T15:00:00Z");

        assertThat(interceptor.preHandle(request, response, handler("takesAsOf"))).isTrue();

        verify(request, never()).getParameterMap();
        verify(request, never()).getParameter(org.mockito.ArgumentMatchers.anyString());
        verify(request, never()).getInputStream();
        verify(request, never()).getReader();
    }

    // ------------------------------------------------------------------ the default is none

    @Test
    void aHandlerWithoutTheAnnotationAcceptsNoParameterAndSaysSo() throws Exception {
        String detail = rejectionOf(requestWith("x", "1"), "takesNothing");

        assertThat(detail).isEqualTo("unknown query parameter(s): \"x\"; this endpoint accepts no query parameters");
    }

    @Test
    void aDeclaredParameterIsAcceptedOnTheHandlerThatDeclaresIt() throws Exception {
        assertThat(interceptor.preHandle(requestWith("as_of", "anything"), response, handler("takesAsOf"))).isTrue();
    }

    @Test
    void aParameterDeclaredByAnotherHandlerIsUnknownHere() throws Exception {
        String detail = rejectionOf(requestWith("as_of", "2026-10-21T15:00:00Z"), "takesNothing");

        assertThat(detail).contains("\"as_of\"");
    }

    @Test
    void theValueIsNotJudgedHereOnlyTheName() throws Exception {
        // A malformed as_of is the controller's to report, with a detail about as_of.
        assertThat(interceptor.preHandle(requestWith("as_of", ""), response, handler("takesAsOf"))).isTrue();
    }

    // ------------------------------------------------------------------ names

    @ParameterizedTest
    @ValueSource(strings = {"asOf", "AS_OF", "asof", "as_of ", " as_of", "as-of", "x"})
    void aNameThatIsNotExactlyTheDeclaredOneIsUnknown(String name) throws Exception {
        String detail = rejectionOf(requestWith(name, "2026-10-21T15:00:00Z"), "takesAsOf");

        assertThat(detail).isEqualTo(
                "unknown query parameter(s): \"" + name + "\"; the only query parameter accepted is as_of");
    }

    @Test
    void everyUnknownNameIsNamedInSortedOrderWhateverOrderTheyArrivedIn() throws Exception {
        String detail = rejectionOf(requestWith("zeta", "1", "as_of", "2026-10-21T15:00:00Z", "alpha", "2"), "takesAsOf");

        assertThat(detail).isEqualTo(
                "unknown query parameter(s): \"alpha\", \"zeta\"; the only query parameter accepted is as_of");
    }

    @Test
    void aHandlerThatDeclaresTwoParametersListsBothInSortedOrder() throws Exception {
        assertThat(interceptor.preHandle(requestWith("status", "open", "page", "2"), response, handler("takesTwo")))
                .isTrue();

        String detail = rejectionOf(requestWith("x", "1"), "takesTwo");

        assertThat(detail).endsWith("; the query parameters accepted are page, status");
    }

    @Test
    void aVeryLongNameIsCutSoTheDetailDoesNotEchoTheWholeRequestLine() throws Exception {
        String name = "n".repeat(500);

        String detail = rejectionOf(requestWith(name, "1"), "takesNothing");

        assertThat(detail).contains("\"" + "n".repeat(64) + "…\"").doesNotContain("n".repeat(65));
    }

    // ------------------------------------------------------------------ a query string that cannot be read

    @ParameterizedTest
    @ValueSource(strings = {"x=%zz", "as_of=%", "as_of=%2", "x=%E9", "%zz=1", "a=1&x=%C0%AF"})
    void aBadPercentEncodingIsTheOneFixedDetailOnAnyHandlerAndRepeatsNothing(String raw) throws Exception {
        for (String handler : List.of("takesNothing", "takesAsOf")) {
            String detail = rejectionOf(requestWithQuery(raw), handler);

            assertThat(detail).isEqualTo("the query string is not valid percent-encoded UTF-8");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"=1", "=", "as_of=2026-10-21T15:00:00Z&=x"})
    void aParameterWithNoNameIsItsOwnFixedDetailOnAnyHandler(String raw) throws Exception {
        for (String handler : List.of("takesNothing", "takesAsOf")) {
            String detail = rejectionOf(requestWithQuery(raw), handler);

            assertThat(detail).isEqualTo("the query string has a parameter with no name");
        }
    }
}
