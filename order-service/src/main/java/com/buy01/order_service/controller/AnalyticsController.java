package com.buy01.order_service.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.buy01.order_service.dtos.response.AnalyticsResponse;
import com.buy01.order_service.security.AuthenticatedUser;
import com.buy01.order_service.security.CurrentUser;
import com.buy01.order_service.service.AnalyticsService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/orders/analytics")
@RequiredArgsConstructor
public class AnalyticsController {
    private final AnalyticsService service;

    @GetMapping("/me")
    public AnalyticsResponse customer(@CurrentUser AuthenticatedUser user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.get(user, false, from, to);
    }

    @GetMapping("/seller")
    public AnalyticsResponse seller(@CurrentUser AuthenticatedUser user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.get(user, true, from, to);
    }
}
