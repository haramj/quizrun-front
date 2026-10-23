package com.quizrun.room;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 서버 1대 안에서만 유효한 락. 서버가 여러 대면 {@link RedisRoomLocks}를 쓴다. */
@Component
@ConditionalOnProperty(name = "quizrun.room-store", havingValue = "memory", matchIfMissing = true)
public class InMemoryRoomLocks implements RoomLocks {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public <T> T run(String code, Supplier<T> task) {
        ReentrantLock lock = locks.computeIfAbsent(code, k -> new ReentrantLock());
        lock.lock();
        try {
            return task.get();
        } finally {
            lock.unlock();
        }
    }
}
