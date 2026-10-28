package com.buy01.order_service.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;

import com.buy01.order_service.exception.UnauthorizedException;

class SecurityComponentsTest {

    private final UserContextFilter filter = new UserContextFilter();
    private final CurrentUserArgumentResolver resolver = new CurrentUserArgumentResolver();

    @Test
    void filter_setsUserWithTrimmedRoles() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "u1");
        request.addHeader("X-User-Roles", " SELLER , ,CLIENT");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getAttribute(UserContextFilter.USER_ATTRIBUTE))
                .isEqualTo(new AuthenticatedUser("u1", List.of("SELLER", "CLIENT")));
    }

    @Test
    void filter_withoutRolesHeader_setsUserWithNoRoles() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "u1");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(((AuthenticatedUser) request.getAttribute(UserContextFilter.USER_ATTRIBUTE)).roles()).isEmpty();
    }

    @Test
    void filter_withBlankUserId_setsNoUser() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", " ");
        var chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(request.getAttribute(UserContextFilter.USER_ATTRIBUTE)).isNull();
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void authenticatedUser_hasRoleIgnoresCase() {
        var user = new AuthenticatedUser("u1", List.of("seller"));

        assertThat(user.hasRole("SELLER")).isTrue();
        assertThat(user.hasRole("CLIENT")).isFalse();
        assertThat(new AuthenticatedUser("u1", null).hasRole("SELLER")).isFalse();
    }

    @Test
    void resolver_supportsOnlyAnnotatedAuthenticatedUser() throws Exception {
        assertThat(resolver.supportsParameter(param("required", 0))).isTrue();
        assertThat(resolver.supportsParameter(param("notAnnotated", 0))).isFalse();
        assertThat(resolver.supportsParameter(param("wrongType", 0))).isFalse();
    }

    @Test
    void resolver_returnsUserFromRequest() throws Exception {
        var user = new AuthenticatedUser("u1", List.of("CLIENT"));
        NativeWebRequest webRequest = webRequestWith(user);

        assertThat(resolver.resolveArgument(param("required", 0), null, webRequest, null)).isSameAs(user);
    }

    @Test
    void resolver_whenRequiredAndMissing_throwsUnauthorized() throws Exception {
        NativeWebRequest webRequest = webRequestWith(null);
        MethodParameter parameter = param("required", 0);

        assertThatThrownBy(() -> resolver.resolveArgument(parameter, null, webRequest, null))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void resolver_whenOptionalAndMissing_returnsNull() throws Exception {
        NativeWebRequest webRequest = webRequestWith(null);

        assertThat(resolver.resolveArgument(param("optional", 0), null, webRequest, null)).isNull();
    }

    private static NativeWebRequest webRequestWith(AuthenticatedUser user) {
        NativeWebRequest webRequest = mock(NativeWebRequest.class);
        when(webRequest.getAttribute(UserContextFilter.USER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST))
                .thenReturn(user);
        return webRequest;
    }

    private static MethodParameter param(String methodName, int index) throws NoSuchMethodException {
        for (Method method : Handlers.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return new MethodParameter(method, index);
            }
        }
        throw new NoSuchMethodException(methodName);
    }

    @SuppressWarnings("unused")
    private static final class Handlers {
        AuthenticatedUser required(@CurrentUser AuthenticatedUser user) {
            return user;
        }

        AuthenticatedUser optional(@CurrentUser(required = false) AuthenticatedUser user) {
            return user;
        }

        AuthenticatedUser notAnnotated(AuthenticatedUser user) {
            return user;
        }

        String wrongType(@CurrentUser String user) {
            return user;
        }
    }
}
