package com.banks.fraud.detection.engine;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Sliding-window transaction counter per account, implemented as a Redis sorted set
 * (the "sliding window log" pattern): each transfer attempt adds a member scored by
 * its timestamp, entries older than the window are evicted, and the remaining
 * cardinality is the transaction count within the last {@code windowSeconds}.
 */
@Service
@RequiredArgsConstructor
public class VelocityService {

    private final StringRedisTemplate redisTemplate;

    public int recordAndCount(String accountId, int windowSeconds) {
        String key = "fraud:velocity:" + accountId;
        long now = System.currentTimeMillis();
        long windowStartExclusive = now - (windowSeconds * 1000L);

        redisTemplate.opsForZSet().add(key, UUID.randomUUID().toString(), now);
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStartExclusive);
        redisTemplate.expire(key, Duration.ofSeconds(windowSeconds + 5L));

        Long count = redisTemplate.opsForZSet().zCard(key);
        return count == null ? 0 : count.intValue();
    }
}
