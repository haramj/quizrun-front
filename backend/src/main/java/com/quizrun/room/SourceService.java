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
                         @Value("${quizrun.sources.thin-chars:2500}") int thinChars) {
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
            wiki = wikipedia.find(keyword);
        } catch (Exception e) {
            error = true;
            log.warn("위키백과 조회 실패({}): {}", keyword, e.toString());
        }
        if (wiki.isPresent() && wiki.get().text().length() >= thinChars) return new Lookup(wiki, false);

        Optional<SourceDocument> namuDoc = Optional.empty();
        if (namuEnabled) {
            try {
                namuDoc = namu.find(keyword);
            } catch (Exception e) {
                error = true;
                log.warn("나무위키 조회 실패({}): {}", keyword, e.toString());
            }
        }
        Optional<SourceDocument> best = wiki;
        if (namuDoc.isPresent() && (wiki.isEmpty() || namuDoc.get().text().length() > wiki.get().text().length())) {
            best = namuDoc;
        }
        best = best.filter(d -> d.text().length() >= MIN_USABLE_CHARS);
        log.info("자료 조회 '{}': {}", keyword, best.map(d -> d.credit() + " " + d.text().length() + "자").orElse("없음"));
        return new Lookup(best, error && best.isEmpty());
    }
}
