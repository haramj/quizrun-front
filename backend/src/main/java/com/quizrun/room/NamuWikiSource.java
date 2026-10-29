package com.quizrun.room;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * 나무위키 문서 본문(CC BY-NC-SA 2.0 KR). 공식 API가 없어 /w/{제목} 페이지의 HTML에서 텍스트를 뽑는다.
 * robots.txt가 /w/ 접근을 허용하지만 문서 하나가 1~2MB라 호출자가 반드시 캐시해야 하고,
 * 위키백과 본문이 얇을 때만 보강용으로 쓴다. 비영리·출처 표기 조건을 지켜야 한다.
 */
@Component
public class NamuWikiSource {

    static final int MAX_CHARS = 12000;
    private static final Pattern TAIL = Pattern.compile("(관련 문서|둘러보기)");

    private final RestClient client;

    public NamuWikiSource(@Value("${quizrun.sources.namu-url:https://namu.wiki}") String baseUrl,
                          @Value("${quizrun.sources.user-agent}") String userAgent) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("User-Agent", userAgent).build();
    }

    public Optional<SourceDocument> find(String keyword) {
        String html;
        try {
            html = client.get().uri("/w/{title}", keyword).retrieve().body(String.class);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty(); // 문서 없음(제목이 정확히 일치해야 한다)
        }
        if (html == null || html.isBlank()) return Optional.empty();
        String text = extract(html);
        if (text.length() < 400) return Optional.empty();
        return Optional.of(new SourceDocument("나무위키", titleOf(html, keyword), text));
    }

    static String titleOf(String html, String fallback) {
        String t = Jsoup.parse(html).title();
        return t.isBlank() ? fallback : t.replace(" - 나무위키", "").trim();
    }

    /** HTML에서 본문 텍스트만 뽑는다. 목차·각주·편집 링크 잡음을 줄이는 휴리스틱이다. */
    static String extract(String html) {
        Document d = Jsoup.parse(html);
        d.select("script,style,noscript,iframe,nav,header,footer").remove();
        String t = d.body() == null ? "" : d.body().text();
        t = t.replaceAll("\\[(\\d+|편집)\\]", "").replaceAll("\\s+", " ").trim();

        // 문서 앞에는 목차가 먼저 나오므로, 앞부분에서 마지막 '개요' 위치부터를 본문으로 본다.
        int window = Math.min(t.length() - 1, 5000);
        int start = window > 0 ? t.lastIndexOf("개요", window) : -1;
        if (start > 0) t = t.substring(start);

        Matcher m = TAIL.matcher(t);
        if (m.find() && m.start() > 400) t = t.substring(0, m.start());
        t = t.trim();
        return t.length() > MAX_CHARS ? t.substring(0, MAX_CHARS) : t;
    }
}
