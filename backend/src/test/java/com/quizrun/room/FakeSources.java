package com.quizrun.room;

import java.util.Optional;

class FakeSources implements SourceFinder {

    boolean available = true;
    int calls;

    @Override
    public Optional<SourceDocument> find(String keyword) {
        calls++;
        return available
                ? Optional.of(new SourceDocument("위키백과", keyword, "자료 본문 ".repeat(100)))
                : Optional.empty();
    }
}
