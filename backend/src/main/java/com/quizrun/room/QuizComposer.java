package com.quizrun.room;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.quizrun.room.Room.Question;

/**
 * 한 판에 낼 문제를 구성한다.
 *
 * <ol>
 *   <li>문제은행(DB)에서 bank-ratio(기본 30%)만큼 가져온다. 덜 쓰인 문제를 먼저 낸다.</li>
 *   <li>나머지(기본 70%)는 근거 자료(위키)로 새로 생성한다. 검증을 통과한 문제는 여분까지 전부 DB에 쌓는다.</li>
 *   <li>모자라면 문제은행에서 더 채우고, 그래도 모자라면 내장 문제로 폴백하거나 오류를 낸다.</li>
 * </ol>
 * 내장 카테고리 이름(상식·개발 등)은 범위가 너무 넓어 근거 자료가 의미 없으므로 AI 생성 없이 문제은행만 쓴다.
 */
@Service
public class QuizComposer {

    private static final Logger log = LoggerFactory.getLogger(QuizComposer.class);

    /** questionIds는 questions와 같은 순서이며, 문제은행에 없는 문제(내장 폴백)는 null이다. 신고할 때 쓴다. */
    public record Quiz(List<Question> questions, String source, String topic, List<String> credits, List<Long> questionIds) {}

    private enum Failure { NONE, NO_SOURCE, GENERATION_FAILED }

    /**
     * 한 판에서 이미 고른 문제들. 본문이 같은 문제뿐 아니라 <b>같은 근거 문장</b>에서 나온 문제도 막는다.
     * (본문이 다르게 표현돼도 같은 사실을 묻는 문제, 예: "생년월일은 언제인가?"와 "생년월일은?")
     */
    private static final class Picked {
        private final Set<String> hashes = new HashSet<>();
        private final Set<String> facts = new HashSet<>();

        boolean accept(StoredQuestion q) {
            String fact = factOf(q);
            if (hashes.contains(q.textHash) || (fact != null && facts.contains(fact))) return false;
            hashes.add(q.textHash);
            if (fact != null) facts.add(fact);
            return true;
        }
    }

    /** 근거 문장의 정규화 키. 근거가 없거나(내장 문제) 너무 짧으면 null. */
    private static String factOf(StoredQuestion q) {
        String f = Texts.norm(q.evidence);
        return f.length() < 6 ? null : f;
    }

    private final QuestionVault vault;
    private final SourceFinder sources;
    private final QuestionGenerator generator;
    private final QuestionBank bank;
    private final double bankRatio;

    public QuizComposer(QuestionVault vault, SourceFinder sources, QuestionGenerator generator, QuestionBank bank,
                        @Value("${quizrun.quiz.bank-ratio:0.3}") double bankRatio) {
        this.vault = vault;
        this.sources = sources;
        this.generator = generator;
        this.bank = bank;
        this.bankRatio = Math.max(0, Math.min(1, bankRatio));
    }

    /**
     * 이 키워드는 AI 생성(수 초~수십 초)이 필요한가? 내장 카테고리 이름이거나 AI가 꺼져 있으면 DB만 쓰므로 즉시 끝난다.
     * RoomService가 느린 경로(백그라운드 준비)와 빠른 경로(바로 구성)를 고르는 데 쓴다.
     */
    public boolean needsGeneration(String keyword) {
        return !QuestionBank.TOPICS.contains(keyword) && generator.enabled();
    }

    public Quiz compose(String keyword, int count) {
        String topicKey = Texts.norm(keyword);
        boolean category = QuestionBank.TOPICS.contains(keyword);

        List<StoredQuestion> pool = new ArrayList<>(vault.findActiveByTopic(topicKey));
        Picked picked = new Picked();
        List<StoredQuestion> fromBank = new ArrayList<>();
        List<StoredQuestion> fresh = new ArrayList<>();

        take(pool, fromBank, picked, category ? count : (int) Math.round(count * bankRatio));

        Failure failure = Failure.NONE;
        int need = count - fromBank.size();
        if (need > 0 && !category && generator.enabled()) {
            Optional<SourceDocument> doc = sources.find(keyword);
            if (doc.isEmpty()) {
                failure = Failure.NO_SOURCE;
            } else if (!generateInto(keyword, topicKey, doc.get(), need, fresh, picked)) {
                failure = Failure.GENERATION_FAILED;
            }
        }

        // 생성이 모자라면 문제은행에서 더 채운다.
        take(pool, fromBank, picked, count - fromBank.size() - fresh.size());

        int have = fromBank.size() + fresh.size();
        if (have < count) {
            boolean aiApplicable = !category && generator.enabled();
            if ((aiApplicable || category) && have >= minimumPlayable(count)) {
                // 관련 없는 내장 문제로 대체하느니(또는 신고로 제외된 문제를 되살리느니),
                // 검증을 통과한 문제만으로 문제 수를 줄여 진행한다.
                log.warn("출제 '{}': 목표 {}문제 중 {}문제만 확보 - 문제 수를 줄여 진행", keyword, count, have);
            } else {
                if (failure == Failure.NONE && aiApplicable) failure = Failure.GENERATION_FAILED;
                return builtinOrFail(keyword, count, failure);
            }
        }

        List<StoredQuestion> selected = new ArrayList<>(fromBank);
        selected.addAll(fresh);
        vault.markUsed(selected);

        Collections.shuffle(selected);
        List<Question> questions = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (StoredQuestion s : selected) {
            questions.add(s.toShuffledQuestion());
            ids.add(s.id);
        }

        String source = fresh.isEmpty() ? "bank" : fromBank.isEmpty() ? "ai" : "mixed";
        Set<String> credits = new LinkedHashSet<>();
        for (StoredQuestion s : selected) if (s.credit() != null) credits.add(s.credit());
        log.info("출제 '{}': 총 {}문제 = 문제은행 {} + 신규 {} → source={}", keyword, questions.size(), fromBank.size(), fresh.size(), source);
        return new Quiz(List.copyOf(questions), source, keyword, List.copyOf(credits), Collections.unmodifiableList(ids));
    }

    /**
     * 자료로 문제를 만들어 저장하고, 이번 판에 쓸 만큼(need)만 fresh에 담는다. 생성 호출이 실패하면 false.
     * 이미 문제은행에 있는 문제(본문 또는 근거 문장이 같은 것)는 새로 저장하지 않아 문제은행이 중복으로 부풀지 않는다.
     */
    private boolean generateInto(String keyword, String topicKey, SourceDocument doc, int need,
                                 List<StoredQuestion> fresh, Picked picked) {
        // 검증에서 탈락하는 문제가 있으므로 넉넉히 요청한다. 남는 문제는 DB에 쌓인다.
        int request = Math.min(12, (int) Math.ceil(need * 1.5) + 1);
        Optional<List<GeneratedQuestion>> generated = generator.generate(keyword, doc, request, need);
        if (generated.isEmpty()) return false;

        // 비활성(신고로 제외된) 문제의 사실도 포함한다: 같은 오답을 새 문제로 되살리지 않기 위해서다.
        Set<String> knownFacts = new HashSet<>();
        for (StoredQuestion q : vault.findAllByTopic(topicKey)) {
            String f = factOf(q);
            if (f != null) knownFacts.add(f);
        }
        List<StoredQuestion> candidates = new ArrayList<>();
        for (GeneratedQuestion g : generated.get()) {
            StoredQuestion c = new StoredQuestion(topicKey, g.question(), g.evidence(), "ai", doc.site(), doc.title());
            String f = factOf(c);
            if (f != null && !knownFacts.add(f)) continue; // 같은 사실을 이미 가지고 있다
            candidates.add(c);
        }
        for (StoredQuestion saved : vault.saveNew(candidates)) {
            if (fresh.size() >= need) break;
            if (picked.accept(saved)) fresh.add(saved);
        }
        return true;
    }

    /** 목표 문제 수를 못 채웠을 때 그래도 한 판을 열 수 있는 최소 문제 수: 절반 이상, 그리고 최소 3개. */
    static int minimumPlayable(int count) {
        return Math.max(3, (count + 1) / 2);
    }

    /** pool에서 덜 쓰인 순(동률은 무작위)으로 최대 n개를 골라 target에 담는다. 같은 사실을 묻는 문제는 건너뛴다. */
    private void take(List<StoredQuestion> pool, List<StoredQuestion> target, Picked picked, int n) {
        if (n <= 0) return;
        List<StoredQuestion> candidates = new ArrayList<>(pool);
        Collections.shuffle(candidates);
        candidates.sort(Comparator.comparingInt(q -> q.timesUsed));
        for (StoredQuestion q : candidates) {
            if (n <= 0) break;
            if (picked.accept(q)) {
                target.add(q);
                n--;
            }
        }
    }

    private Quiz builtinOrFail(String keyword, int count, Failure failure) {
        String category;
        try {
            category = bank.resolveTopic(keyword);
        } catch (ApiException notInBank) {
            if (failure == Failure.NO_SOURCE) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "이 키워드는 참고할 자료를 찾지 못했어요. 다른 키워드나 기본 카테고리를 선택해 주세요.");
            }
            if (failure == Failure.GENERATION_FAILED) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "AI가 문제를 만들지 못했어요. 잠시 후 다시 시도하거나 기본 카테고리를 선택해 주세요.");
            }
            throw notInBank;
        }
        log.info("내장 문제은행으로 폴백 '{}' → {}", keyword, category);
        Set<String> excluded = new HashSet<>();
        for (StoredQuestion q : vault.findAllByTopic(Texts.norm(category))) {
            if (!q.active) excluded.add(Texts.norm(q.text));
        }
        List<Question> questions = bank.make(category, count, excluded);
        // 내장 폴백 문제는 문제은행 행과 연결되지 않으므로 신고 대상이 아니다(id = null).
        List<Long> ids = new ArrayList<>(Collections.nCopies(questions.size(), (Long) null));
        return new Quiz(questions, "bank", category, List.of(), Collections.unmodifiableList(ids));
    }
}
