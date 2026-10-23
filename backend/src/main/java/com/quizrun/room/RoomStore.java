package com.quizrun.room;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 방 저장소 추상화. 구현체: {@link InMemoryRoomStore}(로컬·테스트), {@link RedisRoomStore}(운영: Cloud DB for Cache).
 *
 * <p>수업 Day4 무상태 설계: <b>서버는 상태를 들고 있지 않고 외부 저장소가 들고 있다.</b>
 * 그래서 두 구현체 모두 방을 JSON으로 저장하고, {@link #find}는 항상 <b>개인 복사본(snapshot)</b>을 돌려준다.
 * 호출자가 복사본을 고친 뒤 {@link #save}로 되돌려 써야 반영된다(읽기-수정-쓰기는 {@link RoomLocks}로 직렬화한다).
 */
public interface RoomStore {

    /** 방의 스냅샷. 돌려받은 객체를 고쳐도 save하기 전에는 저장소에 영향이 없다. */
    Optional<Room> find(String code);

    /** 이미 있는 방을 덮어쓴다. 방이 이미 삭제(만료)됐다면 되살리지 않고 무시한다. */
    void save(Room room);

    /** 같은 코드의 방이 없을 때만 저장한다(원자적). 서버가 여러 대여도 코드가 겹치지 않는다. */
    boolean saveIfAbsent(Room room);

    boolean exists(String code);

    Collection<Room> all();

    void delete(String code);

    /** 접속 신호. 방 잠금 없이 가볍게 기록한다(폴링 100명이 락에서 줄 서지 않게). */
    void touch(String code, String playerId, long now);

    /** 참가자별 마지막 접속 신호(epoch ms). */
    Map<String, Long> presence(String code);
}
