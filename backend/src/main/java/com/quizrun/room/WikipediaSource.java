package com.quizrun.room;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 한국어 위키백과 공식 API로 키워드에 가장 맞는 문서 본문을 가져온다.
 * User-Agent가 없으면 403, 짧은 시간에 많이 부르면 429가 오므로 호출자(SourceService)가 캐시한다.
 */
@Component
public class WikipediaSource {

    static final int MAX_CHARS = 6000;
    private static final Pattern TAIL = Pattern.compile("\\n=+ *(같이 보기|각주|외부 링크|참고 문헌|참고 자료|관련 항목|주석) *=+");
    private static final Pattern HEADING = Pattern.compile("(?m)^=+ *(.+?) *=+$");

    private final ObjectMapper mapper;
    private final RestClient client;

    public WikipediaSource(ObjectMapper mapper,
                           @Value("${quizrun.sources.wikipedia-url:https://ko.wikipedia.org}") String baseUrl,
                           @Value("${quizrun.sources.user-agent}") String userAgent) {
        this.mapper = mapper;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("User-Agent", userAgent).build();
    }

    public Optional<SourceDocument> find(String keyword) throws IOException {
        // 1) 제목으로 직접 조회: 리다이렉트를 따라간다. (예: "뉴진스" → "NewJeans" 문서)
        //    사람이 만든 문서/리다이렉트라서 관련성이 보장된다.
        String direct = client.get()
                .uri("/w/api.php?action=query&titles={kw}&redirects=1&prop=extracts&explaintext=1&exlimit=1&format=json", keyword)
                .retrieve().body(String.class);
        Optional<SourceDocument> doc = parse(keyword, direct, mapper, false);
        if (doc.isPresent()) return doc;

        // 2) 제목이 없으면 검색 1위를 쓰되, 검색은 느슨하므로 제목이 키워드와 겹칠 때만 인정한다.
        String search = client.get()
                .uri("/w/api.php?action=query&generator=search&gsrsearch={kw}&gsrlimit=1"
                        + "&prop=extracts&explaintext=1&exlimit=1&redirects=1&format=json", keyword)
                .retrieve().body(String.class);
        return parse(keyword, search, mapper, true);
    }

    static Optional<SourceDocument> parse(String keyword, String raw, ObjectMapper mapper) throws IOException {
        return parse(keyword, raw, mapper, true);
    }

    /**
     * requireTitleMatch: 검색 결과일 때 true — 무의미한 키워드에 검색이 엉뚱한 문서("이름공간")를 돌려주는 일이 있어
     * 제목이 키워드와 겹쳐야 쓴다. 제목 직접 조회(리다이렉트 포함)일 때는 false.
     */
    static Optional<SourceDocument> parse(String keyword, String raw, ObjectMapper mapper, boolean requireTitleMatch)
            throws IOException {
        JsonNode pages = mapper.readTree(raw).path("query").path("pages");
        if (!pages.isObject() || pages.isEmpty()) return Optional.empty();
        JsonNode page = pages.elements().next();
        if (page.has("missing") || page.has("invalid")) return Optional.empty();
        String title = page.path("title").asText("");
        String extract = page.path("extract").asText("");
        if (title.isEmpty() || extract.isEmpty()) return Optional.empty();

        if (requireTitleMatch) {
            String k = Texts.norm(keyword), t = Texts.norm(title);
            if (k.isEmpty() || t.isEmpty() || !(t.contains(k) || k.contains(t))) return Optional.empty();
        }

        String text = clean(extract);
        // 동음이의 문서는 사실 자료가 아니다.
        if (text.length() < 800 && text.contains("가리킬 수 있")) return Optional.empty();
        return Optional.of(new SourceDocument("위키백과", title, text));
    }

    static String clean(String extract) {
        String t = extract.replace("\r", "");
        var m = TAIL.matcher(t);
        if (m.find()) t = t.substring(0, m.start());
        t = HEADING.matcher(t).replaceAll("$1");
        t = t.replaceAll("\\n{2,}", "\n").trim();
        return t.length() > MAX_CHARS ? t.substring(0, MAX_CHARS) : t;
    }
}
