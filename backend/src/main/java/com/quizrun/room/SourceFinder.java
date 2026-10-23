package com.quizrun.room;

import java.util.Optional;

public interface SourceFinder {

    /** 키워드에 해당하는 근거 자료를 찾는다. 없거나 실패하면 empty (예외를 던지지 않는다). */
    Optional<SourceDocument> find(String keyword);
}
