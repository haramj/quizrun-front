package com.quizrun.room;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Cloud DB for Cache(Redis) 방 저장소. 수업 Day4 무상태 설계의 "상태를 서버 밖으로" 구현이다.
 *
 * <pre>
 *  quizrun:room:{code}      STRING  방 전체(JSON). 만료 = 방 TTL
 *  quizrun:rooms            SET     방 코드 색인(목록 조회용)
 *  quizrun:presence:{code}  HASH    참가자별 마지막 접속 시각(방 잠금 없이 갱신)
 *  quizrun:lock:{code}      STRING  분산 락(RedisRoomLocks)
 * </pre>
 * 방 키에 TTL을 걸어서 서버가 없어도 만료된 방이 저절로 사라진다(서버 장애·교체와 무관).
 */
public class RedisRoomStore implements RoomStore {

    static final String ROOM = "quizrun:room:";
    static final String INDEX = "quizrun:rooms";
    static final String PRESENCE = "quizrun:presence:";

    private static final Duration MIN_TTL = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final long roomTtlMs;

    public RedisRoomStore(StringRedisTemplate redis, Clock clock, long roomTtlMs) {
        this.redis = redis;
        this.clock = clock;
        this.roomTtlMs = roomTtlMs;
    }

    @Override
    public Optional<Room> find(String code) {
        return Optional.ofNullable(redis.opsForValue().get(ROOM + code)).map(RoomCodec::decode);
    }

    @Override
    public void save(Room room) {
        // XX: 이미 있는 방만 갱신한다. 다른 서버가 만료·삭제한 방을 되살리지 않는다.
        redis.opsForValue().setIfPresent(ROOM + room.code, RoomCodec.encode(room), ttl(room));
    }

    @Override
    public boolean saveIfAbsent(Room room) {
        // NX: 서버 여러 대가 동시에 같은 코드를 만들어도 한 쪽만 성공한다.
        Boolean created = redis.opsForValue().setIfAbsent(ROOM + room.code, RoomCodec.encode(room), ttl(room));
        if (!Boolean.TRUE.equals(created)) return false;
        redis.opsForSet().add(INDEX, room.code);
        return true;
    }

    @Override
    public boolean exists(String code) {
        return Boolean.TRUE.equals(redis.hasKey(ROOM + code));
    }

    @Override
    public Collection<Room> all() {
        Set<String> codes = redis.opsForSet().members(INDEX);
        if (codes == null || codes.isEmpty()) return List.of();
        List<String> ordered = new ArrayList<>(codes);
        List<String> values = redis.opsForValue().multiGet(ordered.stream().map(c -> ROOM + c).toList());
        List<Room> rooms = new ArrayList<>();
        List<String> gone = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            String json = values == null ? null : values.get(i);
            if (json == null) gone.add(ordered.get(i)); // TTL로 사라진 방: 색인에서 지운다
            else rooms.add(RoomCodec.decode(json));
        }
        if (!gone.isEmpty()) redis.opsForSet().remove(INDEX, gone.toArray());
        return rooms;
    }

    @Override
    public void delete(String code) {
        redis.delete(List.of(ROOM + code, PRESENCE + code));
        redis.opsForSet().remove(INDEX, code);
    }

    @Override
    public void touch(String code, String playerId, long now) {
        String key = PRESENCE + code;
        // 한 번의 왕복으로 갱신 + 만료 연장
        redis.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            byte[] k = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            connection.hashCommands().hSet(k, playerId.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    Long.toString(now).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            connection.keyCommands().pExpire(k, roomTtlMs);
            return null;
        });
    }

    @Override
    public Map<String, Long> presence(String code) {
        Map<Object, Object> raw = redis.opsForHash().entries(PRESENCE + code);
        Map<String, Long> result = new HashMap<>();
        raw.forEach((k, v) -> result.put(k.toString(), Long.parseLong(v.toString())));
        return result;
    }

    /** 방이 만들어진 시각부터 roomTtl이 지나면 만료. 이미 지났어도 최소 1분은 남겨 진행 중 요청이 실패하지 않게 한다. */
    private Duration ttl(Room room) {
        long remaining = room.created + roomTtlMs - clock.millis();
        Duration ttl = Duration.ofMillis(Math.max(remaining, 0));
        return ttl.compareTo(MIN_TTL) < 0 ? MIN_TTL : ttl;
    }
}
