package com.eternax.recon.platform.web;

import java.time.Instant;
import java.util.List;

/** The single error body returned for every non-2xx response. Never contains internals. */
public record ErrorResponse(
        String errorCode,
        String message,
        Instant timestamp,
        String correlationId,
        List<String> details) {}
