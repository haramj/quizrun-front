package com.quizrun.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizrun.room.Room.Question;

/** LLM 응답은 믿지 않는다: 형식이 깨졌거나, 근거가 자료에 없거나, 근거가 정답을 뒷받침하지 않는 문제는 버린다. */
class ClovaQuestionGeneratorTest {

    final ObjectMapper mapper = new ObjectMapper();

    static final String DOC = "손흥민(1992년 7월 8일~)은 대한민국의 축구 선수로, 포지션은 공격수이다. "
            + "현재 미국 메이저 리그 사커의 로스앤젤레스 FC 소속이다. "
            + "2025년 약 2,000만 파운드의 이적료로 토트넘 홋스퍼에서 로스앤젤레스 FC로 이적했다. "
            + "2021-22시즌 프리미어리그 득점왕을 차지했다.";

    static String q(String text, String correct, String wrong, String evidence) {
        return "{\"text\":\"" + text + "\",\"correct\":\"" + correct + "\",\"wrong\":" + wrong
                + ",\"evidence\":\"" + evidence + "\"}";
    }

    static final String WRONG = "[\"골키퍼\",\"수비수\",\"심판\"]";
    static final String GOOD = q("손흥민의 포지션은?", "공격수", WRONG, "포지션은 공격수이다");

    @Test
    void acceptsAGroundedQuestionAndPutsTheCorrectAnswerFirst() {
        List<GeneratedQuestion> qs = ClovaQuestionGenerator.parse("[" + GOOD + "]", mapper, DOC);
        assertThat(qs).hasSize(1);
        Question q = qs.get(0).question();
        assertThat(q.options().get(q.answer())).isEqualTo("공격수");
        assertThat(q.options()).containsExactlyInAnyOrder("공격수", "골키퍼", "수비수", "심판");
        assertThat(qs.get(0).evidence()).contains("공격수");
    }

    @Test
    void parsesRateLimitResetDurations() {
        assertThat(ClovaQuestionGenerator.parseDurationMs("29s")).isEqualTo(29_000);
        assertThat(ClovaQuestionGenerator.parseDurationMs("1m5s")).isEqualTo(65_000);
        assertThat(ClovaQuestionGenerator.parseDurationMs("500ms")).isEqualTo(500);
        assertThat(ClovaQuestionGenerator.parseDurationMs("3")).isEqualTo(3_000);
    }

    @Test
    void salvagesCompleteQuestionsFromATruncatedResponse() {
        String full = q("잘린 문제", "공격수", WRONG, "포지션은 공격수이다");
        String cut = "[" + GOOD + "," + full.substring(0, full.length() - 12);
        assertThat(ClovaQuestionGenerator.parse(cut, mapper, DOC)).hasSize(1);
    }

    @Test
    void toleratesCodeFencesAndProse() {
        String content = "물론이죠!\n```json\n[" + GOOD + "]\n```\n즐거운 시간 되세요.";
        assertThat(ClovaQuestionGenerator.parse(content, mapper, DOC)).hasSize(1);
    }

    @Test
    void toleratesSpacingAndPunctuationDifferencesInEvidence() {
        String ev = "포지션은  공격수이다.";
        assertThat(ClovaQuestionGenerator.parse("[" + q("손흥민의 포지션은?", "공격수", WRONG, ev) + "]", mapper, DOC)).hasSize(1);
    }

    @Test
    void dropsQuestionsWhoseEvidenceIsNotInTheDocument() {
        // 실제 사례: 모델이 기억으로 지어낸 근거 (뉴진스 "Ditto가 핫100 첫 진입곡")
        String made = q("손흥민의 현재 소속팀은?", "토트넘", "[\"LAFC\",\"첼시\",\"리버풀\"]", "현재 토트넘 홋스퍼에서 뛰고 있다");
        assertThat(ClovaQuestionGenerator.parse("[" + made + "]", mapper, DOC)).isEmpty();
    }

    @Test
    void dropsQuestionsWhereEvidenceContradictsTheMarkedAnswer() {
        // 실제 사례: 근거는 "2,000만 파운드"인데 정답을 "3,000만 파운드"로 표시했다
        String wrong = q("이적료는?", "약 3,000만 파운드", "[\"약 1,000만 파운드\",\"약 500만 파운드\",\"약 100만 파운드\"]",
                "2025년 약 2,000만 파운드의 이적료로 토트넘 홋스퍼에서 로스앤젤레스 FC로 이적했다");
        assertThat(ClovaQuestionGenerator.parse("[" + wrong + "]", mapper, DOC)).isEmpty();

        String right = q("이적료는?", "약 2,000만 파운드", "[\"약 1,000만 파운드\",\"약 3,000만 파운드\",\"약 500만 파운드\"]",
                "2025년 약 2,000만 파운드의 이적료로 토트넘 홋스퍼에서 로스앤젤레스 FC로 이적했다");
        assertThat(ClovaQuestionGenerator.parse("[" + right + "]", mapper, DOC)).hasSize(1);
    }

    @Test
    void dropsQuestionsWhoseStemNumbersAreNotInTheEvidence() {
        // 실제 사례: 근거는 2015년 준우승인데 문제 본문은 "2023년"으로 연도를 잘못 붙였다
        String doc = DOC + " 2015년 AFC 아시안컵에서 준우승을 차지했다.";
        String ev = "2015년 AFC 아시안컵에서 준우승을 차지했다";
        String wrongYear = q("손흥민이 2023년 아시안컵에서 거둔 성적은?", "준우승", "[\"우승\",\"8강\",\"4강\"]", ev);
        String rightYear = q("손흥민이 2015년 아시안컵에서 거둔 성적은?", "준우승", "[\"우승\",\"8강\",\"4강\"]", ev);
        assertThat(ClovaQuestionGenerator.parse("[" + wrongYear + "]", mapper, doc)).isEmpty();
        assertThat(ClovaQuestionGenerator.parse("[" + rightYear + "]", mapper, doc)).hasSize(1);
    }

    @Test
    void numbersInSeasonAndAmountNotationAreMatchedAgainstEvidence() {
        assertThat(ClovaQuestionGenerator.hasNumberNotInEvidence("2021-22시즌 득점왕은?", Texts.norm("2021-22시즌 득점왕"))).isFalse();
        assertThat(ClovaQuestionGenerator.hasNumberNotInEvidence("이적료 3,000만 파운드는?", Texts.norm("3,000만 파운드"))).isFalse();
        assertThat(ClovaQuestionGenerator.hasNumberNotInEvidence("2025년 이적료는?", Texts.norm("2024년 이적"))).isTrue();
        assertThat(ClovaQuestionGenerator.hasNumberNotInEvidence("몇 번째 우승인가?", Texts.norm("세 번째 우승"))).isFalse(); // 한 자리 수는 대상 아님
    }

    @Test
    void dropsNegativeFormQuestions() {
        for (String stem : new String[] {"다음 중 포지션이 아닌 것은?", "옳지 않은 설명은?", "포지션으로 틀린 것은?", "공격수를 제외한 포지션은?"}) {
            assertThat(ClovaQuestionGenerator.parse("[" + q(stem, "공격수", WRONG, "포지션은 공격수이다") + "]", mapper, DOC))
                    .as(stem).isEmpty();
        }
    }

    @Test
    void dropsQuestionsWithFillerOptionsLikeNoInformation() {
        String filler = q("손흥민의 포지션은?", "공격수", "[\"골키퍼\",\"정보 없음\",\"심판\"]", "포지션은 공격수이다");
        assertThat(ClovaQuestionGenerator.parse("[" + filler + "]", mapper, DOC)).isEmpty();
    }

    @Test
    void dropsMissingOrTooShortEvidence() {
        String none = "{\"text\":\"손흥민의 포지션은?\",\"correct\":\"공격수\",\"wrong\":" + WRONG + "}";
        String tiny = q("손흥민의 포지션은?", "공격수", WRONG, "공격수");
        assertThat(ClovaQuestionGenerator.parse("[" + none + "," + tiny + "]", mapper, DOC)).isEmpty();
    }

    @Test
    void dropsStructurallyInvalidEntriesButKeepsValidOnes() {
        String content = "[" + String.join(",",
                GOOD,
                q("오답 2개", "공격수", "[\"골키퍼\",\"수비수\"]", "포지션은 공격수이다"),
                q("오답 4개", "공격수", "[\"골키퍼\",\"수비수\",\"심판\",\"감독\"]", "포지션은 공격수이다"),
                q("오답이 정답과 같음", "공격수", "[\"공격수\",\"수비수\",\"심판\"]", "포지션은 공격수이다"),
                q("오답 중복", "공격수", "[\"골키퍼\",\"골키퍼\",\"심판\"]", "포지션은 공격수이다"),
                q("빈 오답", "공격수", "[\"골키퍼\",\"\",\"심판\"]", "포지션은 공격수이다"),
                q("", "공격수", WRONG, "포지션은 공격수이다"),
                q("자료에 따르면 포지션은?", "공격수", WRONG, "포지션은 공격수이다"),
                GOOD) + "]";
        List<GeneratedQuestion> qs = ClovaQuestionGenerator.parse(content, mapper, DOC);
        assertThat(qs).extracting(g -> g.question().text()).containsExactly("손흥민의 포지션은?");
    }

    @Test
    void returnsEmptyForGarbage() {
        assertThat(ClovaQuestionGenerator.parse(null, mapper, DOC)).isEmpty();
        assertThat(ClovaQuestionGenerator.parse("", mapper, DOC)).isEmpty();
        assertThat(ClovaQuestionGenerator.parse("죄송합니다. 만들 수 없어요.", mapper, DOC)).isEmpty();
        assertThat(ClovaQuestionGenerator.parse("[{\"text\": 깨진 json", mapper, DOC)).isEmpty();
        assertThat(ClovaQuestionGenerator.parse("{\"text\":\"객체\"}", mapper, DOC)).isEmpty();
    }

    @Test
    void shufflingOptionsKeepsTheCorrectAnswerText() {
        Question original = new Question("Q", List.of("정답", "b", "c", "d"), 0);
        java.util.Set<Integer> positions = new java.util.HashSet<>();
        for (int i = 0; i < 100; i++) {
            Question s = ClovaQuestionGenerator.shuffleOptions(original);
            assertThat(s.options().get(s.answer())).isEqualTo("정답");
            assertThat(s.options()).containsExactlyInAnyOrder("정답", "b", "c", "d");
            positions.add(s.answer());
        }
        assertThat(positions.size()).as("정답이 0번에 몰리지 않는다").isGreaterThan(1);
    }

    @Test
    void generatorIsDisabledWithoutApiKey() {
        ClovaQuestionGenerator g = new ClovaQuestionGenerator(mapper, "", "https://example.invalid", "HCX-007", 1, 1);
        assertThat(g.enabled()).isFalse();
        assertThat(g.generate("손흥민", new SourceDocument("위키백과", "손흥민", DOC), 4, 3)).isEmpty();
    }
}
