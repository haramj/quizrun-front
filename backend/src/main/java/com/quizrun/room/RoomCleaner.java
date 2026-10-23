package com.quizrun.room;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 1분마다 보관 시간이 지난 방을 정리한다. (Redis 전환 후에는 키 TTL이 이 역할을 대신한다.) */
@Component
public class RoomCleaner {

    private static final Logger log = LoggerFactory.getLogger(RoomCleaner.class);

    private final RoomService rooms;
    private final long ttlMs;

    public RoomCleaner(RoomService rooms, @Value("${quizrun.room-ttl-minutes:360}") long ttlMinutes) {
        this.rooms = rooms;
        this.ttlMs = TimeUnit.MINUTES.toMillis(ttlMinutes);
    }

    @Scheduled(fixedDelay = 60_000)
    void clean() {
        int removed = rooms.removeExpired(ttlMs);
        if (removed > 0) log.info("만료된 방 {}개 정리", removed);
    }
}
