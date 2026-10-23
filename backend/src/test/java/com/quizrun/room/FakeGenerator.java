package com.quizrun.room;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.quizrun.room.Room.Question;

/** 네트워크 없이 AI 생성 성공/실패를 흉내 내는 생성기. */
class FakeGenerator implements QuestionGenerator {

    boolean enabled;
    boolean succeed;
    /** 한 번에 만들어 내는 최대 개수 (검증 탈락을 흉내 내려면 요청보다 작게 둔다). */
    int maxPerCall = 100;
    String prefix = "AI 문제 ";
    String lastKeyword;
    int lastCount;
    int lastAtLeast;
    int calls;
    /** true면 모든 문제가 같은 근거 문장을 가진다(같은 사실을 다르게 물은 문제 흉내). */
    boolean sameEvidence;

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public Optional<List<GeneratedQuestion>> generate(String keyword, SourceDocument doc, int want, int atLeast) {
        calls++;
        lastKeyword = keyword;
        lastCount = want;
        lastAtLeast = atLeast;
        int count = want;
        if (!enabled || !succeed) return Optional.empty();
        List<GeneratedQuestion> qs = new ArrayList<>();
        for (int i = 0; i < Math.min(count, maxPerCall); i++) {
            qs.add(new GeneratedQuestion(new Question(prefix + i, List.of("a", "b", "c", "d"), 2), sameEvidence ? "모두 같은 근거 문장입니다" : "자료 속 근거 문장 번호 " + i));
        }
        return Optional.of(qs);
    }
}
