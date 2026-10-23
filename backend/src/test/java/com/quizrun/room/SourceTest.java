package com.quizrun.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 위키백과/나무위키 본문 정리와, 두 출처를 고르는 정책 검증 (실제 네트워크는 쓰지 않는다). */
class SourceTest {

    final ObjectMapper mapper = new ObjectMapper();

    static String wikiJson(String title, String extract) {
        return "{\"query\":{\"pages\":{\"1\":{\"pageid\":1,\"title\":\"" + title + "\",\"extract\":\""
                + extract.replace("\n", "\\n") + "\"}}}}";
    }

    @Test
    void wikipediaCleansHeadingsAndDropsTrailingSections() throws Exception {
        String extract = "손흥민은 축구 선수이다. ".repeat(30) + "\n\n\n== 경력 ==\n토트넘에서 뛰었다. ".repeat(3)
                + "\n== 같이 보기 ==\n무관한 링크\n== 각주 ==\n각주 내용";
        SourceDocument doc = WikipediaSource.parse("손흥민", wikiJson("손흥민", extract), mapper).orElseThrow();
        assertThat(doc.site()).isEqualTo("위키백과");
        assertThat(doc.title()).isEqualTo("손흥민");
        assertThat(doc.text()).contains("경력").doesNotContain("==").doesNotContain("각주 내용").doesNotContain("무관한 링크");
    }

    @Test
    void wikipediaRejectsIrrelevantSearchHits() throws Exception {
        // 무의미한 키워드에 검색이 엉뚱한 문서("이름공간")를 돌려준 실제 사례
        assertThat(WikipediaSource.parse("존재하지않는키워드xyz", wikiJson("이름공간", "이름공간은 ".repeat(100)), mapper)).isEmpty();
    }

    @Test
    void directTitleLookupFollowsRedirectsEvenWhenTheArticleTitleIsNotKorean() throws Exception {
        // 실제 사례: "뉴진스"는 "NewJeans" 문서로 리다이렉트된다. 검색 결과였다면 제목 불일치로 버려졌을 것이다.
        String json = wikiJson("NewJeans", "뉴진스는 대한민국의 걸그룹이다. ".repeat(100));
        assertThat(WikipediaSource.parse("뉴진스", json, mapper)).as("검색 결과로는 제목 불일치로 거절").isEmpty();
        SourceDocument doc = WikipediaSource.parse("뉴진스", json, mapper, false).orElseThrow();
        assertThat(doc.title()).isEqualTo("NewJeans");
        assertThat(doc.credit()).isEqualTo("위키백과 · NewJeans");
    }

    @Test
    void directTitleLookupTreatsMissingPagesAsNotFound() throws Exception {
        String missing = "{\"query\":{\"pages\":{\"-1\":{\"ns\":0,\"title\":\"로제 아파트\",\"missing\":\"\"}}}}";
        assertThat(WikipediaSource.parse("로제 아파트", missing, mapper, false)).isEmpty();
        String invalid = "{\"query\":{\"pages\":{\"-1\":{\"title\":\"x\",\"invalid\":\"\",\"invalidreason\":\"bad\"}}}}";
        assertThat(WikipediaSource.parse("x", invalid, mapper, false)).isEmpty();
    }

    @Test
    void wikipediaRejectsDisambiguationAndEmptyResults() throws Exception {
        assertThat(WikipediaSource.parse("카리나", wikiJson("카리나", "카리나는 다음을 가리킬 수 있다."), mapper)).isEmpty();
        assertThat(WikipediaSource.parse("카리나", "{\"batchcomplete\":\"\"}", mapper)).isEmpty();
    }

    @Test
    void wikipediaTruncatesLongArticles() throws Exception {
        SourceDocument doc = WikipediaSource.parse("뉴진스", wikiJson("뉴진스", "뉴진스는 걸그룹이다. ".repeat(2000)), mapper).orElseThrow();
        assertThat(doc.text().length()).isEqualTo(WikipediaSource.MAX_CHARS);
    }

    @Test
    void namuExtractSkipsTocFootnotesAndEditLinks() {
        String html = "<html><head><title>손흥민 - 나무위키</title><script>var a=1;</script></head><body>"
                + "<nav>메뉴</nav><div>목차 1. 개요 2. 생애 3. 기록</div>"
                + "<h2>1. 개요 <a>[편집]</a></h2><p>대한민국의 축구 선수<sup>[27]</sup>이다. " + "주 포지션은 윙어이다. ".repeat(30) + "</p>"
                + "<h2>2. 생애</h2><p>" + "1992년 7월 8일에 태어났다. ".repeat(20) + "</p>"
                + "<h2>관련 문서</h2><p>잡다한 링크</p></body></html>";
        String text = NamuWikiSource.extract(html);
        assertThat(text).startsWith("개요").contains("대한민국의 축구 선수이다")
                .doesNotContain("[27]").doesNotContain("[편집]").doesNotContain("var a").doesNotContain("메뉴")
                .doesNotContain("잡다한 링크").doesNotContain("목차");
        assertThat(NamuWikiSource.titleOf(html, "x")).isEqualTo("손흥민");
    }

    // ---- SourceService 정책 ----

    WikipediaSource wiki;
    NamuWikiSource namu;
    MutableClock clock;
    SourceService service;

    @BeforeEach
    void setUp() {
        wiki = mock(WikipediaSource.class);
        namu = mock(NamuWikiSource.class);
        clock = new MutableClock();
        service = new SourceService(wiki, namu, clock, true, 2500);
    }

    static SourceDocument doc(String site, int chars) {
        return new SourceDocument(site, "제목", "가".repeat(chars));
    }

    @Test
    void usesWikipediaAloneWhenItIsLongEnough() throws Exception {
        when(wiki.find(anyString())).thenReturn(Optional.of(doc("위키백과", 5000)));
        assertThat(service.find("뉴진스").orElseThrow().site()).isEqualTo("위키백과");
        verify(namu, never()).find(anyString());
    }

    @Test
    void supplementsWithNamuWhenWikipediaIsThin() throws Exception {
        when(wiki.find(anyString())).thenReturn(Optional.of(doc("위키백과", 800)));
        when(namu.find(anyString())).thenReturn(Optional.of(doc("나무위키", 6000)));
        assertThat(service.find("카리나").orElseThrow().site()).isEqualTo("나무위키");
    }

    @Test
    void keepsThinWikipediaIfNamuIsMissingOrShorter() throws Exception {
        when(wiki.find(anyString())).thenReturn(Optional.of(doc("위키백과", 1500)));
        when(namu.find(anyString())).thenReturn(Optional.empty());
        assertThat(service.find("주제").orElseThrow().site()).isEqualTo("위키백과");
    }

    @Test
    void fallsBackToNamuWhenWikipediaFails() throws Exception {
        when(wiki.find(anyString())).thenThrow(new RuntimeException("429"));
        when(namu.find(anyString())).thenReturn(Optional.of(doc("나무위키", 3000)));
        assertThat(service.find("주제").orElseThrow().site()).isEqualTo("나무위키");
    }

    @Test
    void returnsEmptyWhenNothingUsableExists() throws Exception {
        when(wiki.find(anyString())).thenReturn(Optional.of(doc("위키백과", 100))); // 너무 짧아 쓸 수 없음
        when(namu.find(anyString())).thenReturn(Optional.empty());
        assertThat(service.find("주제")).isEmpty();
    }

    @Test
    void cachesResultsAndNegativeResultsExpire() throws Exception {
        when(wiki.find(anyString())).thenReturn(Optional.of(doc("위키백과", 5000)));
        service.find("뉴진스");
        service.find("뉴진스 ");   // 정규화된 키가 같다
        verify(wiki, times(1)).find(anyString());

        when(wiki.find("없는주제")).thenReturn(Optional.empty());
        when(namu.find("없는주제")).thenReturn(Optional.empty());
        service.find("없는주제");
        service.find("없는주제");
        verify(wiki, times(1)).find("없는주제");
        clock.advance(11 * 60 * 1000); // 없음 캐시 10분 경과
        service.find("없는주제");
        verify(wiki, times(2)).find("없는주제");
    }

    @Test
    void namuCanBeDisabled() throws Exception {
        service = new SourceService(wiki, namu, clock, false, 2500);
        when(wiki.find(anyString())).thenReturn(Optional.of(doc("위키백과", 800)));
        assertThat(service.find("카리나").orElseThrow().site()).isEqualTo("위키백과");
        verify(namu, never()).find(anyString());
    }
}
