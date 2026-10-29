package com.quizrun.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizrun.room.QuizComposer.Quiz;
import com.quizrun.room.Room.Question;

class QuizComposerTest {

    InMemoryQuestionVault vault;
    FakeGenerator generator;
    FakeSources sources;
    QuestionBank bank;
    QuizComposer composer;

    @BeforeEach
    void setUp() throws Exception {
        vault = new InMemoryQuestionVault();
        generator = new FakeGenerator();
        generator.enabled = true;
        generator.succeed = true;
        sources = new FakeSources();
        bank = new QuestionBank(new ObjectMapper());
        new QuestionSeeder(vault, bank).run(null);
        composer = new QuizComposer(vault, sources, generator, bank, 0.3);
    }

    void stock(String topicKey, int n, int timesUsed) {
        stock(topicKey, n, timesUsed, null);
    }

    /** sharedEvidence가 null이면 문제마다 서로 다른 근거 문장을 가진다. */
    void stock(String topicKey, int n, int timesUsed, String sharedEvidence) {
        for (int i = 0; i < n; i++) {
            String evidence = sharedEvidence != null ? sharedEvidence : "저장된 근거 문장 " + timesUsed + " 번호 " + i;
            StoredQuestion s = new StoredQuestion(topicKey, new Question("저장 문제 " + timesUsed + "-" + i,
                    List.of("a", "b", "c", "d"), 1), evidence, "ai", "위키백과", "손흥민");
            s.timesUsed = timesUsed;
            vault.saveNew(List.of(s));
        }
    }

    @Test
    void mixesThirtyPercentFromBankAndSeventyPercentNew() {
        stock("손흥민", 20, 0);
        Quiz quiz = composer.compose("손흥민", 10);

        assertThat(quiz.questions()).hasSize(10);
        long fromBank = quiz.questions().stream().filter(q -> q.text().startsWith("저장 문제")).count();
        long fresh = quiz.questions().stream().filter(q -> q.text().startsWith("AI 문제")).count();
        assertThat(fromBank).isEqualTo(3);
        assertThat(fresh).isEqualTo(7);
        assertThat(quiz.source()).isEqualTo("mixed");
        assertThat(generator.lastCount).as("탈락분을 감안해 넉넉히 요청").isGreaterThan(7);
    }

    @Test
    void everyNewlyGeneratedQuestionIsStoredEvenIfUnusedThisRound() {
        Quiz quiz = composer.compose("손흥민", 4);
        assertThat(quiz.source()).isEqualTo("ai");
        // 4문제를 쓰고 요청한 7문제가 모두 문제은행에 쌓인다 → 쓸수록 문제은행이 커진다
        assertThat(vault.findAllByTopic("손흥민")).hasSize(generator.lastCount);
        assertThat(vault.findAllByTopic("손흥민")).allMatch(q -> "ai".equals(q.origin) && q.evidence != null);
    }

    @Test
    void prefersLeastUsedStoredQuestions() {
        stock("손흥민", 5, 3);   // 많이 쓰인 문제
        stock("손흥민", 5, 0);   // 새 문제
        Quiz quiz = composer.compose("손흥민", 10); // 은행 3개 = 덜 쓰인 것부터
        long fresh = vault.findAllByTopic("손흥민").stream().filter(q -> q.timesUsed == 1 && q.text.startsWith("저장 문제 0-")).count();
        assertThat(fresh).isEqualTo(3);
        assertThat(quiz.questions()).hasSize(10);
    }

    @Test
    void neverRepeatsAQuestionWithinOneQuiz() {
        stock("손흥민", 30, 0);
        generator.prefix = "저장 문제 0-"; // 생성 결과가 이미 저장된 문제와 같은 본문이 되게 한다
        Quiz quiz = composer.compose("손흥민", 12);
        Set<String> texts = new HashSet<>();
        quiz.questions().forEach(q -> texts.add(q.text()));
        assertThat(texts).hasSize(12);
    }

    @Test
    void tapsTheBankWhenGenerationYieldsTooFewValidQuestions() {
        stock("손흥민", 20, 0);
        generator.maxPerCall = 2; // 검증에서 대부분 탈락한 상황
        Quiz quiz = composer.compose("손흥민", 10);
        assertThat(quiz.questions()).hasSize(10);
        assertThat(quiz.source()).isEqualTo("mixed");
    }

    @Test
    void usageCountsGoUpForSelectedQuestionsOnly() {
        stock("손흥민", 20, 0);
        composer.compose("손흥민", 10);
        long used = vault.findAllByTopic("손흥민").stream().filter(q -> q.timesUsed > 0).count();
        assertThat(used).isEqualTo(10);
    }

    @Test
    void storedQuestionsAreReshuffledSoAnswerPositionsCannotBeMemorized() {
        StoredQuestion s = new StoredQuestion("x", new Question("Q", List.of("a", "b", "c", "d"), 1), null, "seed", null, null);
        Set<Integer> positions = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            Question q = s.toShuffledQuestion();
            assertThat(q.options().get(q.answer())).isEqualTo("b");
            positions.add(q.answer());
        }
        assertThat(positions.size()).isGreaterThan(1);
    }

    @Test
    void categoryKeywordsNeverCallAiAndComeFromSeededBank() {
        Quiz quiz = composer.compose("개발", 6);
        assertThat(quiz.source()).isEqualTo("bank");
        assertThat(quiz.questions()).hasSize(6);
        assertThat(generator.calls).isZero();
    }

    @Test
    void creditsListTheSourcesOfUsedQuestions() {
        Quiz quiz = composer.compose("손흥민", 4);
        assertThat(quiz.credits()).containsExactly("위키백과 · 손흥민");
    }

    @Test
    void withoutAiKeyOnlyBankAndBuiltinCategoriesWork() {
        generator.enabled = false;
        assertThat(composer.compose("과학", 4).source()).isEqualTo("bank");
        assertThatThrownBy(() -> composer.compose("손흥민", 4)).isInstanceOf(ApiException.class);

        stock("손흥민", 10, 0);
        assertThat(composer.compose("손흥민", 4).source()).as("쌓인 문제가 있으면 키 없이도 출제").isEqualTo("bank");
    }

    @Test
    void aliasKeywordsStillFallBackToTheBuiltinBank() {
        generator.succeed = false; // AI 실패
        Quiz quiz = composer.compose("축구", 4);   // 별칭 → 스포츠 카테고리
        assertThat(quiz.source()).isEqualTo("bank");
        assertThat(quiz.topic()).isEqualTo("스포츠");
    }

    @Test
    void reportsWhyItFailedForUnknownKeywords() {
        sources.available = false;
        assertThatThrownBy(() -> composer.compose("아무도모르는주제", 4)).hasMessageContaining("참고할 자료");
        sources.available = true;
        generator.succeed = false;
        assertThatThrownBy(() -> composer.compose("아무도모르는주제", 4)).hasMessageContaining("AI가 문제를 만들지 못했어요");
    }

    @Test
    void neverPicksTwoQuestionsAboutTheSameFactInOneQuiz() {
        generator.enabled = false;
        stock("손흥민", 5, 0, "손흥민은 1992년 7월 8일에 태어났다"); // 같은 사실을 다르게 물은 문제 5개
        // 같은 사실은 한 판에 하나만 쓸 수 있으므로 4문제를 채울 수 없다
        assertThatThrownBy(() -> composer.compose("손흥민", 4)).isInstanceOf(ApiException.class);

        stock("손흥민", 5, 1);  // 서로 다른 사실 5개 추가
        Quiz quiz = composer.compose("손흥민", 4);
        long sameFact = quiz.questions().stream().filter(q -> q.text().startsWith("저장 문제 0-")).count();
        assertThat(sameFact).as("같은 사실 문제는 최대 1개").isLessThanOrEqualTo(1);
    }

    @Test
    void doesNotStoreGeneratedQuestionsAboutFactsTheBankAlreadyHas() {
        stock("손흥민", 10, 0);
        generator.sameEvidence = true;  // 새로 생성된 문제가 전부 같은 사실을 가리킨다
        Quiz quiz = composer.compose("손흥민", 4);
        assertThat(quiz.questions()).hasSize(4);
        // 같은 근거 문장끼리는 하나만 저장된다 (7개를 요청했지만 1개만 쌓임)
        long stored = vault.findAllByTopic("손흥민").stream().filter(q -> q.text.startsWith("AI 문제")).count();
        assertThat(stored).isEqualTo(1);
    }

    @Test
    void servesFewerVerifiedQuestionsInsteadOfUnrelatedBuiltinOnes() {
        // 실제 사례: "흑백요리사"에서 AI가 4개만 통과 → 별칭("요리")으로 음식 카테고리(팝콘, 초콜릿)를 내보냈었다
        generator.maxPerCall = 4;
        Quiz quiz = composer.compose("흑백요리사", 6);
        assertThat(quiz.questions()).hasSize(4);
        assertThat(quiz.source()).isEqualTo("ai");
        assertThat(quiz.questions()).allMatch(q -> q.text().startsWith("AI 문제"));
    }

    @Test
    void tooFewVerifiedQuestionsStillFallBack() {
        generator.maxPerCall = 2; // 절반(3개)에 못 미침
        assertThatThrownBy(() -> composer.compose("아무도모르는주제", 6)).isInstanceOf(ApiException.class);
        assertThat(composer.compose("흑백요리사", 6).source()).as("관련 카테고리가 있으면 내장 문제로").isEqualTo("bank");
        assertThat(QuizComposer.minimumPlayable(4)).isEqualTo(3);
        assertThat(QuizComposer.minimumPlayable(6)).isEqualTo(3);
        assertThat(QuizComposer.minimumPlayable(12)).isEqualTo(6);
    }

    @Test
    void deactivatedQuestionsAreNeverServed() {
        stock("손흥민", 10, 0);
        List<StoredQuestion> all = vault.findAllByTopic("손흥민");
        for (int i = 0; i < 6; i++) vault.deactivate(all.get(i).id, "reports");
        Set<String> banned = new HashSet<>();
        for (int i = 0; i < 6; i++) banned.add(all.get(i).text);

        generator.enabled = false;
        for (int round = 0; round < 10; round++) {
            Quiz quiz = composer.compose("손흥민", 4);
            assertThat(quiz.questions()).extracting(Question::text).doesNotContainAnyElementsOf(banned);
        }
    }

    @Test
    void factsOfDeactivatedQuestionsAreNotRegeneratedAndStoredAgain() {
        // 신고로 제외된 문제의 사실(근거 문장)을 AI가 다른 표현으로 다시 만들어도 저장·출제하지 않는다
        StoredQuestion bad = new StoredQuestion("손흥민", new Question("잘못된 문제", List.of("a", "b", "c", "d"), 0),
                "자료 속 근거 문장 번호 0", "ai", "위키백과", "손흥민");
        vault.saveNew(List.of(bad));
        vault.deactivate(bad.id, "reports");

        Quiz quiz = composer.compose("손흥민", 4);
        assertThat(quiz.questions()).hasSize(4);
        assertThat(vault.findAllByTopic("손흥민")).filteredOn(q -> q.evidence.equals("자료 속 근거 문장 번호 0"))
                .as("같은 근거에서 나온 새 문제는 저장되지 않았다").hasSize(1);
        assertThat(quiz.questions()).extracting(Question::text).doesNotContain("AI 문제 0");
    }

    @Test
    void categoryServesFewerQuestionsWhenSomeSeededOnesAreDeactivated() {
        List<StoredQuestion> seeds = vault.findAllByTopic("개발");
        for (int i = 0; i < 5; i++) vault.deactivate(seeds.get(i).id, "reports");
        Quiz quiz = composer.compose("개발", 12);   // 12 - 5 = 7개 남음, 최소 문제 수(6) 이상이므로 7개로 진행
        assertThat(quiz.questions()).hasSize(7);
        assertThat(quiz.source()).isEqualTo("bank");
    }

    @Test
    void builtinFallbackAlsoSkipsDeactivatedQuestions() {
        List<StoredQuestion> seeds = vault.findAllByTopic("개발");
        for (int i = 0; i < 11; i++) vault.deactivate(seeds.get(i).id, "reports");
        Quiz quiz = composer.compose("개발", 12);   // 남은 1개 < 최소 문제 수 → 내장 폴백, 그래도 제외된 문제는 빠진다
        assertThat(quiz.questions()).hasSize(1);
        assertThat(quiz.questions().get(0).text()).isEqualTo(seeds.get(11).text);
        assertThat(quiz.questionIds()).containsExactly((Long) null);
    }

    @Test
    void quizKeepsQuestionIdsAlignedWithQuestions() {
        Quiz quiz = composer.compose("개발", 6);
        assertThat(quiz.questionIds()).hasSize(6).doesNotContainNull();
        for (int i = 0; i < 6; i++) {
            StoredQuestion row = vault.findById(quiz.questionIds().get(i)).orElseThrow();
            assertThat(row.text).isEqualTo(quiz.questions().get(i).text());
        }
    }

    @Test
    void curatedKeywordUsesOnlyTheReviewedSetWithoutAiOrSources() throws Exception {
        CuratedQuestions curated = new CuratedQuestions(new ObjectMapper());
        InMemoryQuestionVault v = new InMemoryQuestionVault();
        new QuestionSeeder(v, bank, curated).run(null);
        QuizComposer c = new QuizComposer(v, sources, generator, bank, 0.3, curated);

        for (String keyword : List.of("네이버 클라우드", "네이버클라우드", "NCP", "Naver Cloud")) {
            assertThat(c.needsGeneration(keyword)).isFalse();
            Quiz quiz = c.compose(keyword, 12);
            assertThat(quiz.questions()).hasSize(12);
            assertThat(quiz.source()).isEqualTo("bank");
            assertThat(quiz.questions()).extracting(Question::text).doesNotContain("AI 문제");
        }
        assertThat(generator.calls).isZero();
        assertThat(c.needsGeneration("손흥민")).isTrue();
    }

    @Test
    void curatedSetIsAddedToAnExistingDatabaseButBaseSeedRunsOnlyOnce() throws Exception {
        CuratedQuestions curated = new CuratedQuestions(new ObjectMapper());
        new QuestionSeeder(vault, bank, curated).run(null); // vault는 이미 기본 문제로 채워져 있다
        long afterFirst = vault.count();
        new QuestionSeeder(vault, bank, curated).run(null);
        assertThat(vault.count()).isEqualTo(afterFirst);
        assertThat(vault.findActiveByTopic("네이버클라우드")).hasSize(15);
    }
}
