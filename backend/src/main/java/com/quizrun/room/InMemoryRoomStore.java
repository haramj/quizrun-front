package com.quizrun.room;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * 서버 메모리 저장소(로컬 개발·자동 테스트용, 서버 1대). {@code quizrun.room-store=memory}(기본값).
 *
 * <p>일부러 Redis와 똑같이 <b>JSON 문자열로 저장하고 복사본을 돌려준다.</b> 객체를 그대로 들고 있으면 "서버가 상태를 들고 있는"
 * 방식이 되어 직렬화 오류나 저장 누락이 테스트에서 숨어 버린다. 이렇게 하면 모든 테스트가 운영(Redis)과 같은 경로를 탄다.
 */
@Repository
@ConditionalOnProperty(name = "quizrun.room-store", havingValue = "memory", matchIfMissing = true)
public class InMemoryRoomStore implements RoomStore {

    private final Map<String, String> rooms = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Long>> presence = new ConcurrentHashMap<>();

    @Override
    public Optional<Room> find(String code) {
        return Optional.ofNullable(rooms.get(code)).map(RoomCodec::decode);
    }

    @Override
    public void save(Room room) {
        rooms.computeIfPresent(room.code, (k, old) -> RoomCodec.encode(room));
    }

    @Override
    public boolean saveIfAbsent(Room room) {
        return rooms.putIfAbsent(room.code, RoomCodec.encode(room)) == null;
    }

    @Override
    public boolean exists(String code) {
        return rooms.containsKey(code);
    }

    @Override
    public Collection<Room> all() {
        return rooms.values().stream().map(RoomCodec::decode).toList();
    }

    @Override
    public void delete(String code) {
        rooms.remove(code);
        presence.remove(code);
    }

    @Override
    public void touch(String code, String playerId, long now) {
        presence.computeIfAbsent(code, k -> new ConcurrentHashMap<>()).put(playerId, now);
    }

    @Override
    public Map<String, Long> presence(String code) {
        return Map.copyOf(presence.getOrDefault(code, Map.of()));
    }
}
