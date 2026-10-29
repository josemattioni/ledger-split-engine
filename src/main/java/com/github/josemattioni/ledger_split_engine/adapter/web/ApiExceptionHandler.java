package com.github.josemattioni.ledger_split_engine.adapter.web;

import com.github.josemattioni.ledger_split_engine.domain.EchoedValue;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountAlreadyExistsException;
import com.github.josemattioni.ledger_split_engine.domain.account.AccountNotFoundException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.AmountLimitExceededException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.AsOfNotSettledException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.CannotReverseAReversalException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.DegenerateJournalEntryException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.EntryAlreadyReversedException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.IdempotencyKeyReusedException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.InvalidAccountCodeException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.JournalEntryNotFoundException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.LineLimitExceededException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.MixedCurrencyException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.UnbalancedJournalEntryException;
import com.github.josemattioni.ledger_split_engine.domain.journalentry.UnknownAccountException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final int MAX_LISTED_VIOLATIONS = 10;

    // Only a request the client got wrong. A bare IllegalArgumentException is not mapped here on
    // purpose: from anywhere else it is an internal defect and falls through to the 500 below.
    @ExceptionHandler(InvalidRequestException.class)
    public ProblemDetail handleInvalidRequest(InvalidRequestException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    // A body past the limit that a handler read itself (the reversal's one-byte check); one read by a message
    // converter arrives wrapped in a HttpMessageNotReadableException instead (see handleHttpMessageNotReadable).
    @ExceptionHandler(RequestBodyTooLargeException.class)
    public ProblemDetail handleRequestBodyTooLarge(RequestBodyTooLargeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, ex.getMessage());
    }

    @ExceptionHandler(AccountAlreadyExistsException.class)
    public ProblemDetail handleAccountAlreadyExists(AccountAlreadyExistsException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    // A domain exception (the use case must not depend on this package), the one 404 for an unknown account, from
    // the account lookup and from the balance read alike: the default problem type, the same detail for both.
    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail handleAccountNotFound(AccountNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    // A domain exception (the use case must not depend on this package), mapped like any other 404:
    // the default problem type, a detail naming the entry_id.
    @ExceptionHandler(JournalEntryNotFoundException.class)
    public ProblemDetail handleJournalEntryNotFound(JournalEntryNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    // One handler per ledger rule: each 422 carries its own problem type (see ProblemTypes).

    @ExceptionHandler(UnbalancedJournalEntryException.class)
    public ProblemDetail handleUnbalanced(UnbalancedJournalEntryException ex) {
        return ledgerRuleViolation(ex, ProblemTypes.UNBALANCED_ENTRY);
    }

    @ExceptionHandler(DegenerateJournalEntryException.class)
    public ProblemDetail handleDegenerate(DegenerateJournalEntryException ex) {
        return ledgerRuleViolation(ex, ProblemTypes.DEGENERATE_ENTRY);
    }

    @ExceptionHandler(UnknownAccountException.class)
    public ProblemDetail handleUnknownAccount(UnknownAccountException ex) {
        return ledgerRuleViolation(ex, ProblemTypes.UNKNOWN_ACCOUNT);
    }

    @ExceptionHandler(MixedCurrencyException.class)
    public ProblemDetail handleMixedCurrency(MixedCurrencyException ex) {
        return ledgerRuleViolation(ex, ProblemTypes.MIXED_CURRENCY);
    }

    @ExceptionHandler(AmountLimitExceededException.class)
    public ProblemDetail handleAmountLimitExceeded(AmountLimitExceededException ex) {
        return ledgerRuleViolation(ex, ProblemTypes.AMOUNT_LIMIT_EXCEEDED);
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ProblemDetail handleIdempotencyKeyReused(IdempotencyKeyReusedException ex) {
        return ledgerRuleViolation(ex, ProblemTypes.IDEMPOTENCY_KEY_REUSED);
    }

    @ExceptionHandler(CannotReverseAReversalException.class)
    public ProblemDetail handleCannotReverseAReversal(CannotReverseAReversalException ex) {
        return ledgerRuleViolation(ex, ProblemTypes.CANNOT_REVERSE_A_REVERSAL);
    }

    @ExceptionHandler(EntryAlreadyReversedException.class)
    public ProblemDetail handleEntryAlreadyReversed(EntryAlreadyReversedException ex) {
        return ledgerRuleViolation(ex, ProblemTypes.ENTRY_ALREADY_REVERSED);
    }

    @ExceptionHandler(AsOfNotSettledException.class)
    public ProblemDetail handleAsOfNotSettled(AsOfNotSettledException ex) {
        return ledgerRuleViolation(ex, ProblemTypes.AS_OF_NOT_SETTLED);
    }

    @ExceptionHandler(LineLimitExceededException.class)
    public ProblemDetail handleLineLimitExceeded(LineLimitExceededException ex) {
        return ledgerRuleViolation(ex, ProblemTypes.LINE_LIMIT_EXCEEDED);
    }

    @ExceptionHandler(InvalidAccountCodeException.class)
    public ProblemDetail handleInvalidAccountCode(InvalidAccountCodeException ex) {
        return ledgerRuleViolation(ex, ProblemTypes.INVALID_ACCOUNT_CODE);
    }

    /**
     * A body that cannot be read is a 400 either way; where the client's mistake can be named without replaying
     * what was sent, say what it is: an unrecognized field is named, and an enum value that is not one of the names
     * gets the field and the names that are allowed, never the value. Every other cause (malformed JSON, a wrong
     * JSON type, a repeated field) keeps Spring's generic detail.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        RequestBodyTooLargeException tooLarge = causeOfType(ex, RequestBodyTooLargeException.class);
        if (tooLarge != null) {
            return handleExceptionInternal(
                    ex,
                    ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, tooLarge.getMessage()),
                    headers,
                    HttpStatus.CONTENT_TOO_LARGE,
                    request);
        }
        String detail = nameableMistake(ex.getCause());
        if (detail == null) {
            return super.handleHttpMessageNotReadable(ex, headers, status, request);
        }
        return handleExceptionInternal(ex, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), headers, status, request);
    }

    /**
     * A required field that is missing, null or blank is a 400 that names it ({@code code must not be blank}), in the
     * request's own snake_case, with a fixed wording per constraint: never Bean Validation's message (it is localized
     * and may carry the constraint's parameters) and never the rejected value. A request can break the rule on every
     * element of a long array, so only the first few are listed.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<String> violations = ex.getBindingResult().getAllErrors().stream()
                .map(ApiExceptionHandler::violationOf)
                .distinct()
                .sorted()
                .toList();
        String detail = violations.stream().limit(MAX_LISTED_VIOLATIONS).collect(Collectors.joining("; "));
        if (violations.size() > MAX_LISTED_VIOLATIONS) {
            detail += "; and " + (violations.size() - MAX_LISTED_VIOLATIONS) + " more";
        }
        return handleExceptionInternal(ex, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), headers, status, request);
    }

    private static String violationOf(ObjectError error) {
        String requirement = switch (String.valueOf(error.getCode())) {
            case "NotNull" -> "must not be null";
            case "NotBlank" -> "must not be blank";
            case "NotEmpty" -> "must not be empty";
            default -> "is invalid";
        };
        if (error instanceof FieldError field) {
            return snakeCase(field.getField()) + " " + requirement;
        }
        return "the request " + requirement;
    }

    /** {@code lines[0].accountCode} as the client wrote it: {@code lines[0].account_code}. */
    private static String snakeCase(String path) {
        return path.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    /** The first cause of this type, however many layers (Jackson's reader, Spring's converter) it was wrapped in. */
    private static <T extends Throwable> T causeOfType(Throwable ex, Class<T> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
        }
        return null;
    }

    private static String nameableMistake(Throwable cause) {
        if (cause instanceof UnrecognizedPropertyException unrecognized) {
            return "unrecognized field " + EchoedValue.quoted(unrecognized.getPropertyName());
        }
        if (cause instanceof MismatchedInputException mismatch
                && mismatch.getTargetType() != null
                && mismatch.getTargetType().isEnum()) {
            String allowed = Arrays.stream(mismatch.getTargetType().getEnumConstants())
                    .map(constant -> ((Enum<?>) constant).name())
                    .collect(Collectors.joining(", "));
            return lastFieldOf(mismatch) + " must be exactly one of " + allowed;
        }
        return null;
    }

    private static String lastFieldOf(MismatchedInputException mismatch) {
        List<JacksonException.Reference> path = mismatch.getPath();
        for (int i = path.size() - 1; i >= 0; i--) {
            if (path.get(i).getPropertyName() != null) {
                return path.get(i).getPropertyName();
            }
        }
        return "the value";
    }

    private static ProblemDetail ledgerRuleViolation(RuntimeException ex, URI type) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setType(type);
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
}
