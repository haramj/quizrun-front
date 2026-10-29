package com.quizrun.room;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizrun.room.Room.Question;

/**
 * 직접 검수한 키워드별 문제 세트(resources/curated/*.json). 이 키워드로 방을 만들면 AI 생성·자료 조회 없이
 * 이 문제들만 출제한다. 시작할 때 문제은행에 적재되고(QuestionSeeder), 신고·비활성화도 일반 문제와 똑같이 적용된다.
 */
@Component
public class CuratedQuestions {

    /** evidence는 문제은행의 같은 사실 중복 판정에 쓰인다. */
    public record Item(Question question, String evidence) {}

    private record Set_(String topic, List<String> keywords, List<Item> items) {}

    private static final List<String> FILES = List.of("curated/ncp.json");

    private final List<Set_> sets;

    public CuratedQuestions(ObjectMapper mapper) throws IOException {
        List<Set_> loaded = new ArrayList<>();
        for (String file : FILES) {
            try (InputStream in = new ClassPathResource(file).getInputStream()) {
                var root = mapper.readTree(in);
                List<String> keywords = new ArrayList<>();
                root.get("keywords").forEach(k -> keywords.add(Texts.norm(k.asText())));
                List<Item> items = new ArrayList<>();
                for (var n : root.get("questions")) {
                    List<String> options = new ArrayList<>();
                    n.get("options").forEach(o -> options.add(o.asText()));
                    items.add(new Item(new Question(n.get("text").asText(), List.copyOf(options), n.get("answer").asInt()),
                            n.get("evidence").asText()));
                }
                loaded.add(new Set_(Texts.norm(root.get("topic").asText()), List.copyOf(keywords), List.copyOf(items)));
            }
        }
        this.sets = List.copyOf(loaded);
    }

    private CuratedQuestions() {
        this.sets = List.of();
    }

    /** 세트가 없는 인스턴스. 큐레이션과 무관한 테스트·구성에서 쓴다. */
    static CuratedQuestions none() {
        return new CuratedQuestions();
    }

    /** 이 키워드에 맞는 세트의 문제은행 topic 키. 없으면 null. 공백·대소문자·기호 차이는 무시한다. */
    String topicFor(String keyword) {
        String k = Texts.norm(keyword);
        for (Set_ s : sets) {
            if (s.keywords().contains(k)) return s.topic();
        }
        return null;
    }

    /** topic 키 → 적재할 문제들. 시더가 쓴다. */
    List<String> topics() {
        return sets.stream().map(Set_::topic).toList();
    }

    List<Item> itemsOf(String topic) {
        return sets.stream().filter(s -> s.topic().equals(topic)).findFirst().map(Set_::items).orElse(List.of());
    }
}
