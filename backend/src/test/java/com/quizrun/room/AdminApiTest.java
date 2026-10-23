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

/** 실제 스프링 컨텍스트 + JPA(H2)로 관리자 API와 신고 API의 HTTP 계약을 검증한다. */
@SpringBootTest(properties = {
        "quizrun.clova.api-key=",
        "quizrun.admin.token=test-admin-token",
        "spring.datasource.url=jdbc:h2:mem:admin;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class AdminApiTest {

    static final String TOKEN = "X-Admin-Token";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    long firstQuestionId() throws Exception {
        String res = mvc.perform(get("/api/admin/questions").param("status", "all").param("topic", "개발").param("limit", "3")
                        .header(TOKEN, "test-admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get(0).get("id").asLong();
    }

    @Test
    void adminApiRequiresTheToken() throws Exception {
        mvc.perform(get("/api/admin/questions")).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/questions").header(TOKEN, "wrong")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").isString());
        mvc.perform(post("/api/admin/questions/1/deactivate").header(TOKEN, "wrong")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/questions/1/reactivate")).andExpect(status().isForbidden());
    }

    @Test
    void adminSeesAnswersAndCanDeactivateThenRestoreAQuestion() throws Exception {
        long id = firstQuestionId();

        mvc.perform(get("/api/admin/questions").param("status", "all").param("topic", "개발").param("limit", "1")
                        .header(TOKEN, "test-admin-token"))
                .andExpect(jsonPath("$[0].answer").isNumber())
                .andExpect(jsonPath("$[0].options.length()").value(4))
                .andExpect(jsonPath("$[0].active").value(true));

        mvc.perform(post("/api/admin/questions/" + id + "/deactivate").header(TOKEN, "test-admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.deactivatedReason").value("admin"));
        mvc.perform(get("/api/admin/questions").param("status", "inactive").header(TOKEN, "test-admin-token"))
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").exists());

        mvc.perform(post("/api/admin/questions/" + id + "/reactivate").header(TOKEN, "test-admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.reportCount").value(0));
    }

    @Test
    void unknownQuestionsAndStatusesAreRejected() throws Exception {
        mvc.perform(post("/api/admin/questions/99999999/deactivate").header(TOKEN, "test-admin-token"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/questions").param("status", "bogus").header(TOKEN, "test-admin-token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reportEndpointValidatesTheRequest() throws Exception {
        String res = mvc.perform(post("/api/rooms").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\":\"과학\",\"name\":\"Host\",\"capacity\":4,\"count\":4}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode created = json.readTree(res);
        String code = created.get("code").asText(), token = created.get("token").asText();

        mvc.perform(post("/api/rooms/" + code + "/report").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"index\":0,\"reason\":\"WRONG_ANSWER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("아직 공개되지 않은 문제는 신고할 수 없어요."));
        mvc.perform(post("/api/rooms/" + code + "/report").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"x\",\"index\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/rooms/" + code + "/report")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/rooms/ZZZZZZ/report").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }
}
