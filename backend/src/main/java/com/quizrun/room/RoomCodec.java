package com.quizrun.room;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * 방 ↔ JSON. 저장소가 메모리든 Redis든 같은 형식을 쓴다.
 * 알 수 없는 필드는 무시한다: 서버를 하나씩 교체하는 배포(롤링) 중에 새 버전이 쓴 필드를 옛 버전이 읽어도 깨지지 않게.
 */
final class RoomCodec {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private RoomCodec() {}

    static String encode(Room room) {
        try {
            return MAPPER.writeValueAsString(room);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("방을 JSON으로 바꾸지 못했어요: " + room.code, e);
        }
    }

    static Room decode(String json) {
        try {
            return MAPPER.readValue(json, Room.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 방을 읽지 못했어요", e);
        }
    }
}
