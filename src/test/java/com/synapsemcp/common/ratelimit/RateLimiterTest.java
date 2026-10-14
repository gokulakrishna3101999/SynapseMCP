package com.synapsemcp.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.synapsemcp.common.exception.ApiException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RateLimiterTest {

    private final RateLimiter rateLimiter = new RateLimiter();
    private final UUID tenantId = UUID.randomUUID();

    @Test
    void allowsRequestsUpToTheLimit() {
        assertThatCode(() -> {
            for (int i = 0; i < 3; i++) {
                rateLimiter.checkAndConsume(tenantId, "OP", 3, Duration.ofMinutes(1));
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void rejectsTheRequestThatExceedsTheLimit() {
        rateLimiter.checkAndConsume(tenantId, "OP", 2, Duration.ofMinutes(1));
        rateLimiter.checkAndConsume(tenantId, "OP", 2, Duration.ofMinutes(1));

        assertThatThrownBy(() -> rateLimiter.checkAndConsume(tenantId, "OP", 2, Duration.ofMinutes(1)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void differentTenantsHaveIndependentBuckets() {
        UUID otherTenant = UUID.randomUUID();
        rateLimiter.checkAndConsume(tenantId, "OP", 1, Duration.ofMinutes(1));

        assertThatCode(() -> rateLimiter.checkAndConsume(otherTenant, "OP", 1, Duration.ofMinutes(1)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> rateLimiter.checkAndConsume(tenantId, "OP", 1, Duration.ofMinutes(1)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void differentOperationsForTheSameTenantHaveIndependentBuckets() {
        rateLimiter.checkAndConsume(tenantId, "UPLOAD", 1, Duration.ofMinutes(1));

        assertThatCode(() -> rateLimiter.checkAndConsume(tenantId, "CHAT", 1, Duration.ofMinutes(1)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> rateLimiter.checkAndConsume(tenantId, "UPLOAD", 1, Duration.ofMinutes(1)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void theWindowResetsAfterItExpires() throws InterruptedException {
        Duration tinyWindow = Duration.ofMillis(100);
        rateLimiter.checkAndConsume(tenantId, "OP", 1, tinyWindow);
        assertThatThrownBy(() -> rateLimiter.checkAndConsume(tenantId, "OP", 1, tinyWindow))
                .isInstanceOf(ApiException.class);

        Thread.sleep(150);

        assertThatCode(() -> rateLimiter.checkAndConsume(tenantId, "OP", 1, tinyWindow))
                .doesNotThrowAnyException();
    }
}
