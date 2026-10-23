package com.quizrun.room;

import com.quizrun.room.Room.Question;

/** 생성기를 통과한 문제와, 정답의 근거가 된 자료 속 문장. */
public record GeneratedQuestion(Question question, String evidence) {}
