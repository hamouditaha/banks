package com.banks.fraud.account.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * The conditional debit is expressed as a Lua script so the read-check-decrement
 * sequence executes as a single atomic operation on the Redis server, removing the
 * need for a client-side compare-and-swap loop.
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisScript<Long> conditionalDebitScript() {
        String script = """
                local balance = tonumber(redis.call('GET', KEYS[1]) or '0')
                local amount = tonumber(ARGV[1])
                if balance >= amount then
                    redis.call('DECRBY', KEYS[1], amount)
                    return 1
                else
                    return 0
                end
                """;
        return new DefaultRedisScript<>(script, Long.class);
    }
}
