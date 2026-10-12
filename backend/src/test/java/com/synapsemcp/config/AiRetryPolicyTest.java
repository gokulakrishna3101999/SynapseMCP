package com.synapsemcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.retry.support.RetryTemplate;

/** Tier 1 decision: a failed Gemini call fails the job. application.yml must leave exactly one attempt. */
@SpringBootTest(classes = AiRetryPolicyTest.Empty.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ImportAutoConfiguration(SpringAiRetryAutoConfiguration.class)
class AiRetryPolicyTest {

    @org.springframework.context.annotation.Configuration
    static class Empty {
    }

    @Autowired
    RetryTemplate retryTemplate;

    @Test
    void transientFailureIsNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> retryTemplate.execute(ctx -> {
            attempts.incrementAndGet();
            throw new TransientAiException("simulated 503");
        })).isInstanceOf(TransientAiException.class);
        assertThat(attempts.get()).isEqualTo(1);
    }
}
