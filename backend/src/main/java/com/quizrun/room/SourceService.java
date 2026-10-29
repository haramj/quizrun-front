package com.quizrun.room;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 근거 자료 조회 정책: 위키백과 → (본문이 얇으면) 나무위키 → 없음.
 * 위키백과는 429, 나무위키는 문서당 1~2MB라서 결과를 캐시한다(성공 6시간 / 없음 10분 / 오류 1분).
 */
@Service
public class SourceService implements SourceFinder {

    private static final Logger log = LoggerFactory.getLogger(SourceService.class);
    private static final int MIN_USABLE_CHARS = 400;
    private static final long FOUND_TTL = TimeUnit.HOURS.toMillis(6);
    private static final long MISSING_TTL = TimeUnit.MINUTES.toMillis(10);
    private static final long ERROR_TTL = TimeUnit.MINUTES.toMillis(1);
    private static final int MAX_ENTRIES = 500;
    private static final long RETRY_DELAY_MS = 700;

    private record Entry(Optional<SourceDocument> doc, long expiresAt) {}

    private record Lookup(Optional<SourceDocument> doc, boolean error) {}

    private final WikipediaSource wikipedia;
    private final NamuWikiSource namu;
    private final boolean namuEnabled;
    private final int thinChars;
    private final Clock clock;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public SourceService(WikipediaSource wikipedia, NamuWikiSource namu, Clock clock,
                         @Value("${quizrun.sources.namu-enabled:true}") boolean namuEnabled,
                         @Value("${quizrun.sources.thin-chars:6000}") int thinChars) {
        this.wikipedia = wikipedia;
        this.namu = namu;
        this.clock = clock;
        this.namuEnabled = namuEnabled;
        this.thinChars = thinChars;
    }

    @Override
    public Optional<SourceDocument> find(String keyword) {
        String key = Texts.norm(keyword);
        long now = clock.millis();
        Entry cached = cache.get(key);
        if (cached != null && cached.expiresAt() > now) return cached.doc();

        Lookup result = lookup(keyword);
        long ttl = result.error() ? ERROR_TTL : result.doc().isPresent() ? FOUND_TTL : MISSING_TTL;
        if (cache.size() >= MAX_ENTRIES) cache.clear();
        cache.put(key, new Entry(result.doc(), now + ttl));
        return result.doc();
    }

    private Lookup lookup(String keyword) {
        boolean error = false;
        Optional<SourceDocument> wiki = Optional.empty();
        try {
            wiki = withRetry(() -> wikipedia.find(keyword));
        } catch (Exception e) {
            error = true;
            log.warn("위키백과 조회 실패({}): {}", keyword, e.toString());
        }
        if (wiki.isPresent() && wiki.get().text().length() >= thinChars) return new Lookup(wiki, false);

        Optional<SourceDocument> namuDoc = Optional.empty();
        if (namuEnabled) {
            try {
                namuDoc = withRetry(() -> namu.find(keyword));
            } catch (Exception e) {
                error = true;
                log.warn("나무위키 조회 실패({}): {}", keyword, e.toString());
            }
        }
        Optional<SourceDocument> best = wiki;
        if (namuDoc.isPresent() && wiki.isPresent() && namuDoc.get().text().length() >= MIN_USABLE_CHARS) {
            // 사실이 많을수록 요청한 문제 수를 채우기 쉬우므로, 얇은 위키백과는 나무위키와 합쳐 쓴다.
            best = Optional.of(merge(wiki.get(), namuDoc.get()));
        } else if (namuDoc.isPresent() && (wiki.isEmpty() || namuDoc.get().text().length() > wiki.get().text().length())) {
            best = namuDoc;
        }
        best = best.filter(d -> d.text().length() >= MIN_USABLE_CHARS);
        log.info("자료 조회 '{}': {}", keyword, best.map(d -> d.credit() + " " + d.text().length() + "자").orElse("없음"));
        return new Lookup(best, error && best.isEmpty());
    }

    /**
     * 일시적인 오류(429·타임아웃·5xx)는 한 번에 포기하지 않고 잠시 뒤 다시 시도한다.
     * 재시도 없이 "자료 없음"으로 처리하면 같은 키워드가 어떤 때는 되고 어떤 때는 안 되는 문제가 생긴다.
     * "문서 없음"(빈 결과)은 오류가 아니므로 재시도하지 않는다.
     */
    private static <T> T withRetry(java.util.concurrent.Callable<T> call) throws Exception {
        long[] delays = {RETRY_DELAY_MS, RETRY_DELAY_MS * 3};
        for (int i = 0; ; i++) {
            try {
                return call.call();
            } catch (Exception e) {
                if (i >= delays.length) throw e;
                log.warn("자료 조회 오류 - {}ms 뒤 재시도({}/{}): {}", delays[i], i + 1, delays.length, e.toString());
                Thread.sleep(delays[i]);
            }
        }
    }

    private static SourceDocument merge(SourceDocument wiki, SourceDocument namu) {
        String text = wiki.text() + "\n\n" + namu.text();
        if (text.length() > WikipediaSource.MAX_CHARS) text = text.substring(0, WikipediaSource.MAX_CHARS);
        return new SourceDocument(wiki.site() + " · " + namu.site(), wiki.title(), text);
    }
}
