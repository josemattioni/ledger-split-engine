package com.github.josemattioni.ledger_split_engine.adapter.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps how much of a request body the API will read: a body past the limit fails the read with a
 * {@link RequestBodyTooLargeException}, which {@code ApiExceptionHandler} answers with 413 (problem+json).
 *
 * <p>Nothing else bounds it (Jackson would parse a 6 MB array in full before the entry's own limit of 100 lines
 * rejected it). The bytes actually read are counted, so a chunked body is limited too.
 *
 * <p>Two traps, explained in PROJECT-CONVENTIONS.md: it applies to <b>every</b> request (a gate on the path disagrees
 * with how Spring matches handlers), and it runs <b>first</b>, ahead of Spring Boot's form-content filter, which reads
 * a form-encoded {@code PUT}, {@code PATCH} or {@code DELETE} body in full. The exception then comes out of that
 * filter, outside {@code ApiExceptionHandler}, so this filter answers it itself with a fixed body.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestBodyLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    RequestBodyLimitFilter(@Value("${ledger.http.max-body-bytes:262144}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(new LimitedRequest(request, maxBytes), response);
        } catch (IOException | ServletException e) {
            RequestBodyTooLargeException tooLarge = tooLargeIn(e);
            if (tooLarge == null || response.isCommitted()) {
                throw e;
            }
            response.reset();
            writeTooLarge(response, tooLarge);
        }
    }

    private static RequestBodyTooLargeException tooLargeIn(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof RequestBodyTooLargeException tooLarge) {
                return tooLarge;
            }
        }
        return null;
    }

    /** The exception's message is built from the limit alone, so it holds nothing of the request and needs no escaping. */
    private static void writeTooLarge(HttpServletResponse response, RequestBodyTooLargeException tooLarge)
            throws IOException {
        byte[] body = ("{\"detail\":\"" + tooLarge.getMessage() + "\",\"status\":413,\"title\":\"Content Too Large\"}")
                .getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/problem+json");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long maxBytes;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedStream(super.getInputStream(), maxBytes, getContentLengthLong() > maxBytes);
            }
            return stream;
        }

        /** The wrapped request's own reader would read around the limit, so the reader is built on the limited stream. */
        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class LimitedStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private final boolean declaredTooLarge;
        private long count;

        LimitedStream(ServletInputStream delegate, long maxBytes, boolean declaredTooLarge) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
            this.declaredTooLarge = declaredTooLarge;
        }

        @Override
        public int read() throws IOException {
            refuseIfDeclaredTooLarge();
            int b = delegate.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            refuseIfDeclaredTooLarge();
            int n = delegate.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void refuseIfDeclaredTooLarge() throws RequestBodyTooLargeException {
            if (declaredTooLarge) {
                throw new RequestBodyTooLargeException(maxBytes);
            }
        }

        private void count(int bytes) throws RequestBodyTooLargeException {
            count += bytes;
            if (count > maxBytes) {
                throw new RequestBodyTooLargeException(maxBytes);
            }
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
