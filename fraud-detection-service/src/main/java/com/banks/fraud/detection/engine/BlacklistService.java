package com.banks.fraud.detection.engine;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Blacklisted account ids, stored as a single Redis Set so membership checks are O(1)
 * and the list can be maintained live (via {@link com.banks.fraud.detection.web.BlacklistController})
 * without redeploying the service.
 */
@Service
@RequiredArgsConstructor
public class BlacklistService {

    private static final String BLACKLIST_KEY = "fraud:blacklist";

    private final StringRedisTemplate redisTemplate;

    public boolean isBlacklisted(String accountId) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(BLACKLIST_KEY, accountId));
    }

    public void add(String accountId) {
        redisTemplate.opsForSet().add(BLACKLIST_KEY, accountId);
    }

    public void remove(String accountId) {
        redisTemplate.opsForSet().remove(BLACKLIST_KEY, accountId);
    }

    public Set<String> all() {
        Set<String> members = redisTemplate.opsForSet().members(BLACKLIST_KEY);
        return members == null ? Set.of() : members;
    }
}
