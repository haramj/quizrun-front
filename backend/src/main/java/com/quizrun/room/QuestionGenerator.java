package com.quizrun.room;

import java.util.List;
import java.util.Optional;

/** 근거 자료로 문제를 만들어 주는 생성기. 실패하면 호출자가 문제은행으로 폴백한다. */
public interface QuestionGenerator {

    /** API 키가 설정돼 생성기를 쓸 수 있는지. */
    boolean enabled();

    /**
     * doc.text()에 적힌 사실만으로 want개를 요청하고, 검증을 통과한 문제만 돌려준다.
     * 통과한 문제가 atLeast개에 못 미치면 재시도한다. 통과한 문제가 하나도 없거나 호출이 실패하면 empty.
     * 예외를 던지지 않는다.
     */
    Optional<List<GeneratedQuestion>> generate(String keyword, SourceDocument doc, int want, int atLeast);
}
