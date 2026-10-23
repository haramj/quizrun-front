package com.quizrun.room;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.quizrun.room.Dtos.*;

@RestController
@RequestMapping("/api")
public class RoomController {

    private static final Map<String, Boolean> OK = Map.of("ok", true);

    private final RoomService rooms;

    public RoomController(RoomService rooms) {
        this.rooms = rooms;
    }

    /** 로드밸런서 헬스체크용 (프론트 계약 유지). */
    @GetMapping("/health")
    public Map<String, Boolean> health() {
        return OK;
    }

    @GetMapping("/rooms")
    public List<RoomSummary> list() {
        return rooms.listWaiting();
    }

    @PostMapping("/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    public JoinResponse create(@RequestBody(required = false) CreateRoomRequest body) {
        return rooms.create(body == null ? new CreateRoomRequest(null, null, null, null, null, null) : body);
    }

    @PostMapping("/rooms/{code}/join")
    public JoinResponse join(@PathVariable String code, @RequestBody(required = false) JoinRequest body) {
        return rooms.join(code, body == null ? null : body.name());
    }

    @GetMapping("/rooms/{code}/state")
    public RoomState state(@PathVariable String code,
                           @RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = authorization == null ? null : authorization.replaceFirst("^Bearer ", "");
        return rooms.state(code, token);
    }

    @PostMapping("/rooms/{code}/start")
    public Map<String, Boolean> start(@PathVariable String code, @RequestBody(required = false) TokenRequest body) {
        rooms.start(code, body == null ? null : body.token());
        return OK;
    }

    @PostMapping("/rooms/{code}/answer")
    public Map<String, Boolean> answer(@PathVariable String code, @RequestBody(required = false) AnswerRequest body) {
        rooms.answer(code, body == null ? null : body.token(), body == null ? null : body.answer());
        return OK;
    }

    /** 문제 신고. recorded=false면 중복 신고이거나 신고 대상이 아닌 문제(내장 폴백)다. */
    @PostMapping("/rooms/{code}/report")
    public Map<String, Boolean> report(@PathVariable String code, @RequestBody(required = false) ReportRequest body) {
        if (body == null) throw ApiException.badRequest("신고 내용을 보내 주세요.");
        boolean recorded = rooms.report(code, body.token(), body.index(), body.reason(), body.detail());
        return Map.of("ok", true, "recorded", recorded);
    }

    @PostMapping("/rooms/{code}/chat")
    public Map<String, Boolean> chat(@PathVariable String code, @RequestBody(required = false) ChatRequest body) {
        rooms.chat(code, body == null ? null : body.token(), body == null ? null : body.text());
        return OK;
    }
}
