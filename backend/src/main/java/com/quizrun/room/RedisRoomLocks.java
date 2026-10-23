package com.quizrun.room;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;

/**
 * Redis 분산 락: {@code SET key token NX PX ttl}로 잡고, 내 토큰일 때만 지운다(Lua 한 번에 비교+삭제).
 *
 * <ul>
 *   <li>TTL(기본 10초): 락을 잡은 서버가 죽어도 락이 영원히 남지 않는다.</li>
 *   <li>락 안에서는 외부 호출(위키·LLM)을 하지 않는다. 가장 오래 걸려도 Redis 왕복 몇 번 + DB 한두 번이다.</li>
 *   <li>대기 한도(기본 5초)를 넘으면 503으로 빨리 실패한다.</li>
 * </ul>
 * 알려진 한계: 락 보유 중 TTL보다 오래 멈추면(긴 GC 등) 두 서버가 동시에 들어갈 수 있다. 이 서비스의 락 구간은 수 ms라 위험이 작다.
 */
public class RedisRoomLocks implements RoomLocks {

    static final String PREFIX = "quizrun:lock:";
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final Duration leaseTime;
    private final Duration waitLimit;

    public RedisRoomLocks(StringRedisTemplate redis, Duration leaseTime, Duration waitLimit) {
        this.redis = redis;
        this.leaseTime = leaseTime;
        this.waitLimit = waitLimit;
    }

    @Override
    public <T> T run(String code, Supplier<T> task) {
        String key = PREFIX + code;
        String token = UUID.randomUUID().toString();
        long giveUpAt = System.nanoTime() + waitLimit.toNanos();
        while (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, leaseTime))) {
            if (System.nanoTime() > giveUpAt) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "요청이 몰려 처리하지 못했어요. 잠시 후 다시 시도해 주세요.");
            }
            try {
                Thread.sleep(3 + ThreadLocalRandom.current().nextInt(8)); // 약간의 무작위로 동시에 재시도하는 쏠림을 줄인다
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "요청이 중단됐어요.");
            }
        }
        try {
            return task.get();
        } finally {
            redis.execute(RELEASE, List.of(key), token);
        }
    }
}
