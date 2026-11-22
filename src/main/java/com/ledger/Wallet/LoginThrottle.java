package com.ledger.Wallet;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Slows down password guessing: 5 failed sign-ins per email within 15 minutes locks that email out for a while
@Component
public class LoginThrottle {

    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public void check(String key) {
        Deque<Instant> recent = failures.get(key);
        if (recent == null) {
            return;
        }
        synchronized (recent) {
            prune(recent);
            if (recent.size() >= MAX_FAILURES) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                        "Too many sign-in attempts. Please try again in a few minutes.");
            }
        }
    }

    public void recordFailure(String key) {
        Deque<Instant> recent = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (recent) {
            prune(recent);
            recent.addLast(Instant.now());
        }
    }

    public void reset(String key) {
        failures.remove(key);
    }

    // Forget everything (used by tests)
    public void clear() {
        failures.clear();
    }

    private static void prune(Deque<Instant> recent) {
        Instant cutoff = Instant.now().minus(WINDOW);
        while (!recent.isEmpty() && recent.peekFirst().isBefore(cutoff)) {
            recent.removeFirst();
        }
    }
}
