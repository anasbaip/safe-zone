package com.buy01.order_service.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the user the gateway authenticated (X-User-Id / X-User-Roles headers).
 * When required (the default) a request without those headers is rejected with 401.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface CurrentUser {

    boolean required() default true;
}
