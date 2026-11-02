package com.buy01.order_service.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.buy01.order_service.dtos.response.AnalyticsResponse;
import com.buy01.order_service.exception.GlobalExceptionHandler;
import com.buy01.order_service.exception.BadRequestException;
import com.buy01.order_service.exception.UnauthorizedException;
import com.buy01.order_service.repository.AnalyticsRepository;
import com.buy01.order_service.security.AuthenticatedUser;
import com.buy01.order_service.security.CurrentUserArgumentResolver;
import com.buy01.order_service.security.UserContextFilter;
import com.buy01.order_service.service.AnalyticsService;

class AnalyticsControllerTest {
    private final AnalyticsRepository repository = mock(AnalyticsRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-02T12:00:00Z"), ZoneOffset.UTC);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new AnalyticsController(new AnalyticsService(repository, clock)))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new UserContextFilter()).build();
        when(repository.summarize(anyString(), anyBoolean(), any(), any())).thenAnswer(call ->
                new AnalyticsResponse(call.getArgument(2), call.getArgument(3), "DELIVERED", BigDecimal.ZERO,
                        0, 0, List.of(), List.of(), List.of()));
    }

    @Test
    void defaultsToTwelveCalendarMonthsAndIgnoresCallerSuppliedIdentity() throws Exception {
        mvc.perform(get("/orders/analytics/me").header("X-User-Id", "client-1")
                        .header("X-User-Roles", "CLIENT").param("userId", "victim"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.from").value("2025-12-01"))
                .andExpect(jsonPath("$.to").value("2026-11-02"))
                .andExpect(jsonPath("$.countedStatus").value("DELIVERED"));
        verify(repository).summarize("client-1", false, LocalDate.parse("2025-12-01"), LocalDate.parse("2026-11-02"));
    }

    @Test
    void sellerUsesOnlyAuthenticatedSellerIdentityAndExplicitDates() throws Exception {
        mvc.perform(get("/orders/analytics/seller").header("X-User-Id", "seller-1")
                        .header("X-User-Roles", "SELLER").param("from", "2026-01-01").param("to", "2026-01-31"))
                .andExpect(status().isOk());
        verify(repository).summarize("seller-1", true, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"));
    }

    @Test
    void unauthenticatedAndWrongRoleRequestsCannotReachTheRepository() throws Exception {
        mvc.perform(get("/orders/analytics/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/orders/analytics/seller").header("X-User-Id", "c").header("X-User-Roles", "CLIENT"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/orders/analytics/me").header("X-User-Id", "s").header("X-User-Roles", "SELLER"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(repository);
    }

    @Test
    void malformedReversedOversizedAndExtremeDatesAreRejected() throws Exception {
        for (String[] dates : List.of(new String[]{"bad", "2026-01-01"},
                new String[]{"2026-02-01", "2026-01-01"}, new String[]{"2020-01-01", "2026-01-01"},
                new String[]{"1969-12-31", "1970-01-01"}, new String[]{"9999-01-01", "9999-01-02"})) {
            mvc.perform(get("/orders/analytics/me").header("X-User-Id", "c").header("X-User-Roles", "CLIENT")
                            .param("from", dates[0]).param("to", dates[1])).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(repository);
    }

    @Test
    void missingIdentityIsRejectedEvenForDirectServiceCall() {
        var service = new AnalyticsService(repository, clock);
        assertThatThrownBy(() -> service.get(null, false, null, null)).isInstanceOf(UnauthorizedException.class);
        var blankUser = new AuthenticatedUser(" ", List.of("CLIENT"));
        assertThatThrownBy(() -> service.get(blankUser, false, null, null)).isInstanceOf(UnauthorizedException.class);
        var nullIdentity = new AuthenticatedUser(null, List.of("CLIENT"));
        assertThatThrownBy(() -> service.get(nullIdentity, false, null, null)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void extremeEndDateIsRejectedBeforeDefaultRangeArithmetic() {
        var service = new AnalyticsService(repository, clock);
        var user = new AuthenticatedUser("c1", List.of("CLIENT"));
        LocalDate extreme = LocalDate.MIN;
        assertThatThrownBy(() -> service.get(user, false, null, extreme)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void leapYearAndSameDayRangesAreAccepted() throws Exception {
        mvc.perform(get("/orders/analytics/me").header("X-User-Id", "c").header("X-User-Roles", "CLIENT")
                        .param("from", "2024-01-01").param("to", "2024-12-31"))
                .andExpect(status().isOk());
        mvc.perform(get("/orders/analytics/me").header("X-User-Id", "c").header("X-User-Roles", "CLIENT")
                        .param("from", "2026-01-01").param("to", "2026-01-01"))
                .andExpect(status().isOk());
    }
}
