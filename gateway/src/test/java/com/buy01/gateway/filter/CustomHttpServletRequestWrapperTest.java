package com.buy01.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class CustomHttpServletRequestWrapperTest {

    @Test
    void putHeader_overridesAndAdds() {
        MockHttpServletRequest original = new MockHttpServletRequest();
        original.addHeader("Existing", "orig");
        CustomHttpServletRequestWrapper wrapper = new CustomHttpServletRequestWrapper(original);

        wrapper.putHeader("X-User-Id", "u1");
        wrapper.putHeader("Existing", "replaced");

        assertThat(wrapper.getHeader("X-User-Id")).isEqualTo("u1");
        assertThat(wrapper.getHeader("Existing")).isEqualTo("replaced");
    }

    @Test
    void getHeader_fallsBackToOriginal() {
        MockHttpServletRequest original = new MockHttpServletRequest();
        original.addHeader("Original", "value");
        CustomHttpServletRequestWrapper wrapper = new CustomHttpServletRequestWrapper(original);

        assertThat(wrapper.getHeader("Original")).isEqualTo("value");
        assertThat(wrapper.getHeader("Missing")).isNull();
    }

    @Test
    void getHeaders_returnsCustomThenOriginal() {
        MockHttpServletRequest original = new MockHttpServletRequest();
        original.addHeader("Original", "value");
        CustomHttpServletRequestWrapper wrapper = new CustomHttpServletRequestWrapper(original);
        wrapper.putHeader("X-User-Id", "u1");

        assertThat(Collections.list(wrapper.getHeaders("X-User-Id"))).containsExactly("u1");
        assertThat(Collections.list(wrapper.getHeaders("Original"))).containsExactly("value");
    }

    @Test
    void getHeaderNames_containsBothSets() {
        MockHttpServletRequest original = new MockHttpServletRequest();
        original.addHeader("Original", "value");
        CustomHttpServletRequestWrapper wrapper = new CustomHttpServletRequestWrapper(original);
        wrapper.putHeader("X-User-Id", "u1");

        assertThat(Collections.list(wrapper.getHeaderNames()))
                .contains("Original", "X-User-Id");
    }
    @Test
    void removedIdentityIsAbsentFromEveryHeaderAccessPathRegardlessOfCase() {
        var original = new MockHttpServletRequest();
        original.addHeader("x-user-id", "victim");
        original.addHeader("X-User-Roles", "CLIENT");
        var wrapper = new CustomHttpServletRequestWrapper(original);
        wrapper.removeHeader("X-User-Id"); wrapper.removeHeader("X-User-Roles");
        assertThat(wrapper.getHeader("x-USER-id")).isNull();
        assertThat(Collections.list(wrapper.getHeaders("x-user-id"))).isEmpty();
        assertThat(Collections.list(wrapper.getHeaderNames())).doesNotContain("x-user-id", "X-User-Roles");
        wrapper.putHeader("X-User-Id", "authenticated");
        assertThat(wrapper.getHeader("x-user-id")).isEqualTo("authenticated");
        assertThat(Collections.list(wrapper.getHeaders("x-user-id"))).containsExactly("authenticated");
    }
}