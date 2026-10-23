package com.quizrun.room;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 프론트(public/app.js)가 의존하는 HTTP 계약을 실제 스프링 컨텍스트로 검증한다. */
// 개발자 셸에 NCP_CLOVA_API_KEY가 있어도 테스트가 실제 LLM을 호출하지 않도록 키를 비운다.
@SpringBootTest(properties = {"quizrun.clova.api-key=", "spring.datasource.url=jdbc:h2:mem:contract;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class ApiContractTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void healthEndpointForLoadBalancer() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void createRoomThenPollStateWithBearerToken() throws Exception {
        String res = mvc.perform(post("/api/rooms").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\":\"과학\",\"name\":\"Poll runner\",\"capacity\":4,\"count\":4}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").isString())
                .andExpect(jsonPath("$.token").isString())
                .andReturn().getResponse().getContentAsString();
        JsonNode created = json.readTree(res);
        String code = created.get("code").asText();
        String token = created.get("token").asText();

        mvc.perform(get("/api/rooms/" + code + "/state"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("다시 입장해 주세요."));

        String state = mvc.perform(get("/api/rooms/" + code + "/state").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("waiting"))
                .andExpect(jsonPath("$.prepare").value("ready"))
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.question").doesNotExist())
                .andExpect(jsonPath("$.players[0].online").value(true))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(state).doesNotContain(token);
    }

    @Test
    void unknownRoomIs404AndBadJsonIs400() throws Exception {
        mvc.perform(post("/api/rooms/ZZZZZZ/join").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isString());
        mvc.perform(post("/api/rooms").contentType(MediaType.APPLICATION_JSON).content("{\"capacity\":\"abc\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/nothing")).andExpect(status().isNotFound());
    }

    @Test
    void adminApiIsDisabledWithoutAnAdminToken() throws Exception {
        mvc.perform(get("/api/admin/questions").header("X-Admin-Token", "anything"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("관리자 기능이 꺼져 있어요. ADMIN_TOKEN을 설정해 주세요."));
    }
}
