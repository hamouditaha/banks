package com.banks.fraud.detection.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Recent fraud decisions, stored newest-first in a capped Redis List ({@code fraud:evaluations})
 * so analysts can see what the engine approved or rejected and why.
 */
@Service
@RequiredArgsConstructor
public class EvaluationLogService {

    private static final String LOG_KEY = "fraud:evaluations";
    private static final int MAX_ENTRIES = 500;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @SneakyThrows(JsonProcessingException.class)
    public void record(EvaluationRecord evaluation) {
        redisTemplate.opsForList().leftPush(LOG_KEY, objectMapper.writeValueAsString(evaluation));
        redisTemplate.opsForList().trim(LOG_KEY, 0, MAX_ENTRIES - 1);
    }

    public List<EvaluationRecord> recent(int limit) {
        List<String> raw = redisTemplate.opsForList().range(LOG_KEY, 0, Math.max(0, limit - 1));
        if (raw == null) {
            return List.of();
        }
        return raw.stream().map(this::read).toList();
    }

    @SneakyThrows(JsonProcessingException.class)
    private EvaluationRecord read(String json) {
        return objectMapper.readValue(json, EvaluationRecord.class);
    }
}
