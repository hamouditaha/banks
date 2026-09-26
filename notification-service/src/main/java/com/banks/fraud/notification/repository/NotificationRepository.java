package com.banks.fraud.notification.repository;

import com.banks.fraud.notification.domain.Notification;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Notifications per account, stored as a Redis List ({@code notifications:{accountId}})
 * newest-first, capped at {@link #MAX_PER_ACCOUNT} entries.
 */
@Repository
@RequiredArgsConstructor
public class NotificationRepository {

    private static final int MAX_PER_ACCOUNT = 200;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @SneakyThrows(JsonProcessingException.class)
    public void add(Notification notification) {
        String key = key(notification.getAccountId());
        redisTemplate.opsForList().leftPush(key, objectMapper.writeValueAsString(notification));
        redisTemplate.opsForList().trim(key, 0, MAX_PER_ACCOUNT - 1);
    }

    public List<Notification> findByAccountId(String accountId) {
        List<String> raw = redisTemplate.opsForList().range(key(accountId), 0, -1);
        if (raw == null) {
            return List.of();
        }
        return raw.stream().map(this::read).toList();
    }

    private String key(String accountId) {
        return "notifications:" + accountId;
    }

    @SneakyThrows(JsonProcessingException.class)
    private Notification read(String json) {
        return objectMapper.readValue(json, Notification.class);
    }
}
