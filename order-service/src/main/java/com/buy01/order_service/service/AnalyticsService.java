package com.buy01.order_service.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;

import com.buy01.order_service.dtos.response.AnalyticsResponse;
import com.buy01.order_service.exception.BadRequestException;
import com.buy01.order_service.exception.ForbiddenException;
import com.buy01.order_service.exception.UnauthorizedException;
import com.buy01.order_service.repository.AnalyticsRepository;
import com.buy01.order_service.security.AuthenticatedUser;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AnalyticsService {
    private static final String INVALID_RANGE = "Use an ordered date range of at most 366 days, between years 1970 and 9998";
    private final AnalyticsRepository repository;
    private final Clock clock;

    public AnalyticsResponse get(AuthenticatedUser user, boolean seller, LocalDate from, LocalDate to) {
        if (user == null || user.userId() == null || user.userId().isBlank()) {
            throw new UnauthorizedException("Authentication required");
        }
        if (!user.hasRole(seller ? "SELLER" : "CLIENT")) {
            throw new ForbiddenException("Your role cannot access these analytics");
        }
        LocalDate end = to == null ? LocalDate.now(clock) : to;
        if (end.getYear() < 1970 || end.getYear() > 9998) {
            throw new BadRequestException(INVALID_RANGE);
        }
        LocalDate start = from == null ? end.minusMonths(11).withDayOfMonth(1) : from;
        if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) > 365
                || start.getYear() < 1970) {
            throw new BadRequestException(INVALID_RANGE);
        }
        return repository.summarize(user.userId(), seller, start, end);
    }
}
