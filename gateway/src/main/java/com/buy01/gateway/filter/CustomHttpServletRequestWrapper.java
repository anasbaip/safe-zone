package com.buy01.gateway.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.*;

public class CustomHttpServletRequestWrapper extends HttpServletRequestWrapper {

    private final Map<String, String> customHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Set<String> removedHeaders = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    public CustomHttpServletRequestWrapper(HttpServletRequest request) {
        super(request);
    }

    public void putHeader(String name, String value) {
        removedHeaders.remove(name);
        customHeaders.put(name, value);
    }

    public void removeHeader(String name) {
        customHeaders.remove(name);
        removedHeaders.add(name);
    }

    @Override
    public String getHeader(String name) {
        String customHeader = customHeaders.get(name);

        if (customHeader != null) {
            return customHeader;
        }
        if (removedHeaders.contains(name)) {
            return null;
        }

        return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        String customHeader = customHeaders.get(name);

        if (customHeader != null) {
            return Collections.enumeration(List.of(customHeader));
        }
        if (removedHeaders.contains(name)) {
            return Collections.emptyEnumeration();
        }

        return super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        Set<String> headerNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        headerNames.addAll(customHeaders.keySet());

        Enumeration<String> originalHeaders = super.getHeaderNames();

        while (originalHeaders.hasMoreElements()) {
            String name = originalHeaders.nextElement();
            if (!removedHeaders.contains(name)) {
                headerNames.add(name);
            }
        }

        return Collections.enumeration(headerNames);
    }
}
