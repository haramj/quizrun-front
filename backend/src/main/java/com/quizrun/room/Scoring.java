package com.quizrun.room;

public final class Scoring {

    private Scoring() {}

    /** 정답 1,000점 + 남은 시간 비율에 따른 최대 500점 보너스. */
    public static int score(long elapsedMs, long durationMs) {
        double remaining = Math.max(0, Math.min(1, 1 - (double) elapsedMs / durationMs));
        return 1000 + (int) Math.round(500 * remaining);
    }
}
