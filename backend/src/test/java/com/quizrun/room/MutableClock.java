package com.quizrun.room;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 테스트에서 시간을 임의로 흘려보내기 위한 시계. */
class MutableClock extends Clock {

    private long millis = 1_700_000_000_000L;

    void advance(long ms) {
        millis += ms;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis);
    }

    @Override
    public long millis() {
        return millis;
    }
}
