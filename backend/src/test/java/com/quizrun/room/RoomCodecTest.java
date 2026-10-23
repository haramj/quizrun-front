package com.quizrun.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.quizrun.room.Room.ChatMessage;
import com.quizrun.room.Room.Phase;
import com.quizrun.room.Room.Player;
import com.quizrun.room.Room.Prepare;
import com.quizrun.room.Room.Question;

/** 방을 JSON으로 저장했다가 복원해도 모든 값이 그대로여야 한다 (Redis에 저장하는 형식). */
class RoomCodecTest {

    static Room richRoom() {
        Room r = new Room("ABC123", "손흥민 한 판 달려!", "손흥민", 4, 10, 6, 1_700_000_000_000L);
        Player host = new Player("p1", "tokenA", "러너A", "#d9ff43", 1_700_000_000_500L);
        Player guest = new Player("p2", "tokenB", "러너B", "#b9a7ff", 1_700_000_001_000L);
        host.score = 2750; host.correct = 2; host.lastPoints = 1400; host.answer = 1; host.answeredAt = 1_700_000_009_000L; host.lastChat = 5;
        guest.answer = null;
        r.players.put(host.id, host);
        r.players.put(guest.id, guest);
        r.host = host.id;
        r.messages.add(new ChatMessage("m1", "p1", "러너A", "안녕! <b>html</b> 한글 😀", 1_700_000_002_000L));
        List<Long> ids = new ArrayList<>();
        ids.add(42L);
        ids.add(null);                           // 내장 폴백 문제는 문제은행 ID가 없다
        r.applyQuiz(new QuizComposer.Quiz(
                List.of(new Question("생년월일은?", List.of("a", "b", "c", "d"), 2),
                        new Question("소속팀은?", List.of("w", "x", "y", "z"), 0)),
                "mixed", "손흥민", List.of("위키백과 · 손흥민"), ids));
        r.phase = Phase.PLAYING;
        r.index = 1;
        r.deadline = 1_700_000_020_000L;
        r.startedAt = 1_700_000_010_000L;
        return r;
    }

    @Test
    void roundTripKeepsEverything() {
        Room original = richRoom();
        Room copy = RoomCodec.decode(RoomCodec.encode(original));
        assertThat(copy).usingRecursiveComparison().isEqualTo(original);
        assertThat(copy).isNotSameAs(original);
        assertThat(copy.questionIds).containsExactly(42L, null);
        assertThat(copy.players.keySet()).containsExactly("p1", "p2");   // 입장 순서 유지
        assertThat(copy.messages.get(0).text()).contains("한글 😀");
    }

    @Test
    void preparingRoomWithNullsRoundTrips() {
        Room r = new Room("XYZ999", "t", "k", 2, 5, 4, 1L);
        r.prepare = Prepare.PREPARING;
        r.startRequested = true;
        Room copy = RoomCodec.decode(RoomCodec.encode(r));
        assertThat(copy).usingRecursiveComparison().isEqualTo(r);
        assertThat(copy.deadline).isNull();
        assertThat(copy.source).isNull();
        assertThat(copy.total()).isEqualTo(4);
    }

    @Test
    void unknownFieldsFromANewerServerVersionAreIgnored() {
        // 서버를 하나씩 교체하는 배포 중에, 새 버전이 추가한 필드를 옛 버전이 읽어도 깨지지 않아야 한다
        String json = RoomCodec.encode(richRoom());
        String withExtra = json.substring(0, json.length() - 1) + ",\"fieldAddedInTheFuture\":{\"a\":1}}";
        assertThat(RoomCodec.decode(withExtra).code).isEqualTo("ABC123");
    }
}
