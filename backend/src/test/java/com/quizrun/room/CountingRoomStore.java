package com.quizrun.room;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** 저장소 호출 횟수를 세는 감시용 래퍼. "읽기 요청이 쓰기를 하지 않는다"를 증명하는 데 쓴다. */
class CountingRoomStore implements RoomStore {

    final RoomStore delegate;
    final AtomicInteger saves = new AtomicInteger();
    final AtomicInteger touches = new AtomicInteger();

    CountingRoomStore(RoomStore delegate) {
        this.delegate = delegate;
    }

    @Override public Optional<Room> find(String code) { return delegate.find(code); }
    @Override public void save(Room room) { saves.incrementAndGet(); delegate.save(room); }
    @Override public boolean saveIfAbsent(Room room) { return delegate.saveIfAbsent(room); }
    @Override public boolean exists(String code) { return delegate.exists(code); }
    @Override public Collection<Room> all() { return delegate.all(); }
    @Override public void delete(String code) { delegate.delete(code); }
    @Override public void touch(String code, String playerId, long now) { touches.incrementAndGet(); delegate.touch(code, playerId, now); }
    @Override public Map<String, Long> presence(String code) { return delegate.presence(code); }
}
