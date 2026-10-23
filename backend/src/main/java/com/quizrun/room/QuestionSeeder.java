package com.quizrun.room;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.quizrun.room.Room.Question;

/** 문제은행이 비어 있으면 직접 검수한 내장 문제 180개(15개 카테고리)를 처음 한 번 넣는다. */
@Component
public class QuestionSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(QuestionSeeder.class);

    private final QuestionVault vault;
    private final QuestionBank bank;

    public QuestionSeeder(QuestionVault vault, QuestionBank bank) {
        this.vault = vault;
        this.bank = bank;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (vault.count() > 0) return;
        int total = 0;
        for (Map.Entry<String, List<Question>> e : bank.all().entrySet()) {
            List<StoredQuestion> rows = new ArrayList<>();
            for (Question q : e.getValue()) {
                rows.add(new StoredQuestion(Texts.norm(e.getKey()), q, null, "seed", null, null));
            }
            total += vault.saveNew(rows).size();
        }
        log.info("문제은행 초기 데이터 {}문제 적재", total);
    }
}
