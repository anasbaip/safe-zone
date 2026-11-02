package com.buy01.order_service.dtos.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The part of another service's error body that Order Service passes on. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstreamError(String message) {
}
