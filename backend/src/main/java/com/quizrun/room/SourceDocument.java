package com.quizrun.room;

/** 문제의 근거가 되는 자료(위키 문서 본문). 생성기는 이 text에 적힌 사실로만 출제한다. */
public record SourceDocument(String site, String title, String text) {

    public String credit() {
        return site + " · " + title;
    }
}
