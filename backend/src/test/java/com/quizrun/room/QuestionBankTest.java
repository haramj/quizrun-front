package com.quizrun.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizrun.room.Room.Question;

class QuestionBankTest {

    final QuestionBank bank;

    QuestionBankTest() throws Exception {
        bank = new QuestionBank(new ObjectMapper());
    }

    @Test
    void everyCategoryHasTwelveDistinctValidQuestions() {
        Set<String> all = new HashSet<>();
        for (String topic : QuestionBank.TOPICS) {
            List<Question> qs = bank.make(topic, 12);
            assertThat(qs).hasSize(12);
            for (Question q : qs) {
                assertThat(q.options()).hasSize(4).doesNotHaveDuplicates();
                assertThat(q.answer()).isBetween(0, 3);
                assertThat(all.add(q.text())).as("중복 문제: " + q.text()).isTrue();
            }
        }
        assertThat(all).hasSize(180);
    }

    @Test
    void shufflingKeepsCorrectAnswerText() {
        Question original = bank.make("스포츠", 12).stream()
                .filter(q -> q.text().startsWith("골프에서 기준 타수보다 한 타 적게"))
                .findFirst().orElseThrow();
        String correct = original.options().get(original.answer());
        for (int i = 0; i < 20; i++) {
            Question again = bank.make("스포츠", 12).stream()
                    .filter(q -> q.text().equals(original.text())).findFirst().orElseThrow();
            assertThat(again.options().get(again.answer())).isEqualTo(correct);
        }
    }

    @Test
    void resolvesAliasesAndRejectsUnknown() {
        assertThat(bank.resolveTopic("영어")).isEqualTo("언어");
        assertThat(bank.resolveTopic("축구")).isEqualTo("스포츠");
        assertThat(bank.resolveTopic("React")).isEqualTo("개발");
        assertThatThrownBy(() -> bank.resolveTopic("unsupported")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> bank.make("수학", 13)).isInstanceOf(ApiException.class);
    }

    @Test
    void scoringIsBounded() {
        assertThat(Scoring.score(0, 10000)).isEqualTo(1500);
        assertThat(Scoring.score(5000, 10000)).isEqualTo(1250);
        assertThat(Scoring.score(10000, 10000)).isEqualTo(1000);
        assertThat(Scoring.score(99999, 10000)).isEqualTo(1000);
        assertThat(Scoring.score(1000, 10000)).isGreaterThan(Scoring.score(8000, 10000));
    }
}
