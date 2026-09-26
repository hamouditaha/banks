package com.banks.fraud.orchestrator.repository;

import com.banks.fraud.orchestrator.domain.SagaState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Redis-backed persistence for saga instances.
 *
 * Key layout:
 *  - saga:{sagaId}   -> JSON blob of {@link SagaState}
 *  - sagas:index     -> Set of all saga ids, for listing/demo purposes
 */
@Repository
@RequiredArgsConstructor
public class SagaRepository {

    private static final String SAGA_KEY_PREFIX = "saga:";
    private static final String INDEX_KEY = "sagas:index";
    private static final Duration SAGA_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @SneakyThrows(JsonProcessingException.class)
    public void save(SagaState saga) {
        redisTemplate.opsForValue().set(key(saga.getSagaId()), objectMapper.writeValueAsString(saga), SAGA_TTL);
        redisTemplate.opsForSet().add(INDEX_KEY, saga.getSagaId());
    }

    public Optional<SagaState> findById(String sagaId) {
        String json = redisTemplate.opsForValue().get(key(sagaId));
        return json == null ? Optional.empty() : Optional.of(readSaga(json));
    }

    public List<SagaState> findAll() {
        Set<String> ids = redisTemplate.opsForSet().members(INDEX_KEY);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream().map(this::findById).flatMap(Optional::stream).toList();
    }

    private String key(String sagaId) {
        return SAGA_KEY_PREFIX + sagaId;
    }

    @SneakyThrows(JsonProcessingException.class)
    private SagaState readSaga(String json) {
        return objectMapper.readValue(json, SagaState.class);
    }
}
