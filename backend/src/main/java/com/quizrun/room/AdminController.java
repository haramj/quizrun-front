package com.quizrun.room;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quizrun.room.Dtos.AdminQuestion;

/**
 * 문제은행 관리자 API. 로그인 시스템이 없으므로 환경변수 ADMIN_TOKEN을 X-Admin-Token 헤더로 받는다.
 * ADMIN_TOKEN을 설정하지 않으면 모든 관리자 API가 꺼진다(403). 운영에서는 ALB/ACG로 접근도 제한할 것.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final QuestionReportService service;
    private final String adminToken;

    public AdminController(QuestionReportService service, @Value("${quizrun.admin.token:}") String adminToken) {
        this.service = service;
        this.adminToken = adminToken == null ? "" : adminToken.trim();
    }

    /** status: reported(기본, 신고 많은 순) · inactive · active · all */
    @GetMapping("/questions")
    public List<AdminQuestion> list(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                    @RequestParam(required = false) String topic,
                                    @RequestParam(defaultValue = "reported") String status,
                                    @RequestParam(defaultValue = "50") int limit) {
        authorize(token);
        return service.list(topic, status, limit).stream().map(AdminController::view).toList();
    }

    /** 문제를 출제에서 제외한다(삭제하지 않고 비활성화). */
    @PostMapping("/questions/{id}/deactivate")
    public AdminQuestion deactivate(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                    @PathVariable Long id) {
        authorize(token);
        return view(service.deactivate(id));
    }

    /** 제외한 문제를 복구한다. 신고 기록과 횟수는 초기화된다. */
    @PostMapping("/questions/{id}/reactivate")
    public AdminQuestion reactivate(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                    @PathVariable Long id) {
        authorize(token);
        return view(service.reactivate(id));
    }

    private void authorize(String given) {
        if (adminToken.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "관리자 기능이 꺼져 있어요. ADMIN_TOKEN을 설정해 주세요.");
        }
        boolean ok = given != null && MessageDigest.isEqual(
                adminToken.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
        if (!ok) throw new ApiException(HttpStatus.FORBIDDEN, "관리자 토큰이 올바르지 않아요.");
    }

    private static AdminQuestion view(StoredQuestion q) {
        return new AdminQuestion(q.id, q.topic, q.text, List.of(q.option1, q.option2, q.option3, q.option4), q.answer,
                q.evidence, q.origin, q.credit(), q.reportCount, q.active, q.deactivatedReason, q.timesUsed);
    }
}
