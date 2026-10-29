package com.github.josemattioni.ledger_split_engine.adapter.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The query parameters an endpoint reads, by exact name. A handler method without this annotation accepts none:
 * {@link QueryParameterInterceptor} rejects any other parameter with a 400 before the handler runs, so an
 * endpoint that starts to read a parameter has to say so here, and one that does not cannot be handed one by
 * mistake.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AcceptedQueryParameters {

    String[] value();
}
