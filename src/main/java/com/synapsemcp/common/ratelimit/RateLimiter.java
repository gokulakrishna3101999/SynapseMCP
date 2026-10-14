package com.synapsemcp.common.ratelimit;

import com.synapsemcp.common.exception.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Per-tenant, per-operation fixed-window rate limiter (architecture.md's "Rate Limiting Filter"), scoped
 * to the expensive, real-money operations only (document upload, chat/RAG query, knowledge-base search,
 * MCP ingestion-status checks - the user's own explicit scoping decision). Cheap reads (document list,
 * key management, the REST polling status endpoint) are deliberately never passed through this.
 * <p>In-memory, single-instance only - consistent with {@code IngestionProgressSseService}'s own
 * in-memory map and the rest of this backend's current single-node assumption; horizontally scaling
 * this service would need a shared store (e.g. Redis) instead. The bucket map also never evicts old
 * entries, which is an unbounded-but-slow memory growth for a very long-running process with many
 * distinct tenants - accepted for now, each entry is a few bytes.
 */
@Component
public class RateLimiter {

    /** The user's chosen shape: per-tenant, strict. */
    public static final int STRICT_LIMIT = 10;
    public static final Duration STRICT_WINDOW = Duration.ofMinutes(1);

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public void checkAndConsume(UUID tenantId, String operation, int limit, Duration window) {
        String key = tenantId + ":" + operation;
        Window w = windows.computeIfAbsent(key, k -> new Window());
        synchronized (w) {
            Instant now = Instant.now();
            if (now.isAfter(w.windowStart.plus(window))) {
                w.windowStart = now;
                w.count = 0;
            }
            if (w.count >= limit) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED",
                        "Too many " + operation + " requests; please try again in a moment.");
            }
            w.count++;
        }
    }

    private static final class Window {
        Instant windowStart = Instant.now();
        int count;
    }
}
