package com.quizrun.room;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.quizrun.room.Room.Question;

/** 15개 카테고리 × 12문제 문제은행. 정답이 들어 있으므로 서버 밖으로 내보내지 않는다. */
@Component
public class QuestionBank {

    public static final List<String> TOPICS = List.of(
            "상식", "개발", "과학", "역사", "스포츠", "영화", "음악", "지리",
            "음식", "동물", "언어", "수학", "미술", "게임", "환경");

    private static final Map<String, Pattern> ALIASES = Map.ofEntries(
            Map.entry("개발", Pattern.compile("코딩|자바|react|javascript|컴퓨터|프로그래밍", Pattern.CASE_INSENSITIVE)),
            Map.entry("과학", Pattern.compile("우주|물리|생물")),
            Map.entry("역사", Pattern.compile("한국사|조선")),
            Map.entry("스포츠", Pattern.compile("축구|농구|야구|운동")),
            Map.entry("영화", Pattern.compile("시네마|무비")),
            Map.entry("음악", Pattern.compile("노래|악기")),
            Map.entry("상식", Pattern.compile("종합|일반")),
            Map.entry("지리", Pattern.compile("나라|수도|세계지도")),
            Map.entry("음식", Pattern.compile("요리|먹거리")),
            Map.entry("동물", Pattern.compile("곤충|포유류")),
            Map.entry("언어", Pattern.compile("영어|국어|단어")),
            Map.entry("수학", Pattern.compile("계산|숫자")),
            Map.entry("미술", Pattern.compile("그림|색채")),
            Map.entry("게임", Pattern.compile("체스|오목|스도쿠")),
            Map.entry("환경", Pattern.compile("생태|재활용|에너지")));

    private final Map<String, List<Question>> banks;

    public QuestionBank(ObjectMapper mapper) throws IOException {
        try (InputStream in = new ClassPathResource("questions.json").getInputStream()) {
            this.banks = mapper.readValue(in, new TypeReference<Map<String, List<Question>>>() {});
        }
    }

    /** 내장 문제 전체 (카테고리 → 문제들). 시더가 DB 초기 적재에 쓴다. */
    public Map<String, List<Question>> all() {
        return banks;
    }

    public String resolveTopic(String keyword) {
        String value = keyword == null ? "" : keyword.trim();
        for (String topic : TOPICS) {
            if (value.equals(topic)) return topic;
        }
        for (String topic : TOPICS) {
            if (value.contains(topic)) return topic;
        }
        for (String topic : TOPICS) {
            Pattern alias = ALIASES.get(topic);
            if (alias != null && alias.matcher(value).find()) return topic;
        }
        throw ApiException.badRequest("지원하는 카테고리를 선택해 주세요: " + String.join(" · ", TOPICS));
    }

    /** 문제 순서와 보기 순서를 모두 섞어 count개를 뽑는다. */
    public List<Question> make(String keyword, int count) {
        return make(keyword, count, Set.of());
    }

    /**
     * excludedNormTexts(Texts.norm한 본문)에 해당하는 문제는 뺀다 — 신고로 제외된 내장 문제를 되살리지 않기 위해서다.
     * 제외 후 남은 문제가 count보다 적으면 남은 만큼만 낸다.
     */
    public List<Question> make(String keyword, int count, Set<String> excludedNormTexts) {
        List<Question> bank = banks.get(resolveTopic(keyword));
        if (count < 1 || count > bank.size()) {
            throw ApiException.badRequest("문제 수는 1~" + bank.size() + "개여야 해요.");
        }
        List<Question> shuffled = new ArrayList<>(bank);
        shuffled.removeIf(q -> excludedNormTexts.contains(Texts.norm(q.text())));
        if (shuffled.isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "이 카테고리의 문제가 모두 제외되어 출제할 수 없어요.");
        }
        Collections.shuffle(shuffled);
        List<Question> result = new ArrayList<>();
        for (Question q : shuffled.subList(0, Math.min(count, shuffled.size()))) {
            List<Integer> order = new ArrayList<>(List.of(0, 1, 2, 3));
            Collections.shuffle(order);
            List<String> options = new ArrayList<>();
            for (int i : order) options.add(q.options().get(i));
            result.add(new Question(q.text(), List.copyOf(options), order.indexOf(q.answer())));
        }
        return result;
    }
}
