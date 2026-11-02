package com.buy01.order_service.security;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.buy01.order_service.exception.UnauthorizedException;

public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(AuthenticatedUser.class)
                && parameter.hasParameterAnnotation(CurrentUser.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object user = webRequest.getAttribute(UserContextFilter.USER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        CurrentUser annotation = parameter.getParameterAnnotation(CurrentUser.class);
        boolean required = annotation == null || annotation.required();
        if (user == null && required) {
            throw new UnauthorizedException("Missing or invalid authentication context");
        }
        return user;
    }
}
