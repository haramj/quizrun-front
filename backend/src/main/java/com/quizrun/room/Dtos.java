package com.quizrun.room;

import java.util.List;

/** 프론트(public/app.js)와 맺은 JSON 계약. 필드 이름을 바꾸면 프론트가 깨진다. */
public final class Dtos {

    private Dtos() {}

    public record CreateRoomRequest(String keyword, String title, String name,
                                    Number capacity, Number timeLimit, Number count) {}

    public record JoinRequest(String name) {}

    public record TokenRequest(String token) {}

    public record AnswerRequest(String token, Integer answer) {}

    public record ChatRequest(String token, String text) {}

    /** index: 신고할 문제 번호(0부터, state의 index와 같은 기준). reason: WRONG_ANSWER · AMBIGUOUS · OFFENSIVE · OTHER */
    public record ReportRequest(String token, Integer index, String reason, String detail) {}

    public record DeactivateRequest(String reason) {}

    /** 관리자용: 정답과 근거까지 포함한다. 플레이어에게는 절대 내려가지 않는다. */
    public record AdminQuestion(Long id, String topic, String text, List<String> options, int answer,
                                String evidence, String origin, String source, int reportCount, boolean active,
                                String deactivatedReason, int timesUsed) {}

    public record JoinResponse(String code, String token) {}

    public record RoomSummary(String code, String title, String keyword, int count, int capacity,
                              int total, int timeLimit, String source, String prepare) {}

    public record PublicPlayer(String id, String name, String color, int score, int correct,
                               int lastPoints, boolean online, boolean answered) {}

    public record QuestionView(String text, List<String> options) {}

    /** 정답(answer)은 reveal 단계에서만 채워진다. 토큰은 절대 포함하지 않는다. */
    public record RoomState(long serverTime, String code, String title, String keyword, String source, List<String> credits,
                            String prepare, String prepareError, int capacity,
                            String phase, int index, int total, Long deadline, int timeLimit,
                            List<Room.ChatMessage> messages, String host, String you,
                            List<PublicPlayer> players, QuestionView question, Integer answer,
                            Integer yourAnswer) {}
}
