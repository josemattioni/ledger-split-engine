package com.github.josemattioni.ledger_split_engine.adapter.web;

import com.github.josemattioni.ledger_split_engine.domain.EchoedValue;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Rejects, before any handler runs, a query parameter the endpoint does not accept (see
 * {@link AcceptedQueryParameters}; no annotation means none). Registered for every {@code /api/v1} path by
 * {@link WebConfiguration}.
 *
 * <p>An unknown parameter is rejected, not ignored: a misspelled {@code asOf} silently dropped would answer a request
 * for a past balance with the current one and report success. Names match exactly, on every endpoint, so a new
 * endpoint cannot forget the rule. The names come from {@link QueryString}, so a form-encoded body is never read here.
 * Nothing is examined when the request has no query string. The rule: PROJECT-CONVENTIONS.md.
 */
final class QueryParameterInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || request.getQueryString() == null) {
            return true;
        }
        Set<String> accepted = acceptedBy(method);

        TreeSet<String> unknown = new TreeSet<>(QueryString.of(request).names());
        unknown.removeAll(accepted);
        if (!unknown.isEmpty()) {
            throw new InvalidRequestException("unknown query parameter(s): "
                    + unknown.stream().map(EchoedValue::quoted).collect(Collectors.joining(", "))
                    + "; " + describe(accepted));
        }
        return true;
    }

    private static Set<String> acceptedBy(HandlerMethod method) {
        AcceptedQueryParameters declared = method.getMethodAnnotation(AcceptedQueryParameters.class);
        return declared == null ? Set.of() : Set.of(declared.value());
    }

    private static String describe(Set<String> accepted) {
        return switch (accepted.size()) {
            case 0 -> "this endpoint accepts no query parameters";
            case 1 -> "the only query parameter accepted is " + accepted.iterator().next();
            default -> "the query parameters accepted are " + String.join(", ", new TreeSet<>(accepted));
        };
    }
}
