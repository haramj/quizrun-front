package com.quizrun.room;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 방 저장소를 Cloud DB for Cache(Redis)로 쓰는 설정. {@code quizrun.room-store=redis}일 때만 활성화된다.
 * 접속 정보는 환경변수(REDIS_HOST·REDIS_PORT·REDIS_PASSWORD·REDIS_SSL)로 받는다.
 */
@Configuration
@ConditionalOnProperty(name = "quizrun.room-store", havingValue = "redis")
class RedisRoomConfig {

    @Bean
    LettuceConnectionFactory redisConnectionFactory(
            @Value("${quizrun.redis.host:localhost}") String host,
            @Value("${quizrun.redis.port:6379}") int port,
            @Value("${quizrun.redis.password:}") String password,
            @Value("${quizrun.redis.ssl:false}") boolean ssl,
            @Value("${quizrun.redis.timeout-ms:2000}") long timeoutMs) {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(host, port);
        if (!password.isBlank()) server.setPassword(password);
        LettuceClientConfiguration.LettuceClientConfigurationBuilder client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(timeoutMs))
                .shutdownTimeout(Duration.ofMillis(100));
        if (ssl) client.useSsl();
        return new LettuceConnectionFactory(server, client.build());
    }

    @Bean
    StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Bean
    RoomStore roomStore(StringRedisTemplate redis, Clock clock,
                        @Value("${quizrun.room-ttl-minutes:360}") long ttlMinutes) {
        return new RedisRoomStore(redis, clock, TimeUnit.MINUTES.toMillis(ttlMinutes));
    }

    @Bean
    RoomLocks roomLocks(StringRedisTemplate redis,
                        @Value("${quizrun.redis.lock-lease-ms:10000}") long leaseMs,
                        @Value("${quizrun.redis.lock-wait-ms:5000}") long waitMs) {
        return new RedisRoomLocks(redis, Duration.ofMillis(leaseMs), Duration.ofMillis(waitMs));
    }
}
