package com.quizrun.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties={"quizrun.clova.api-key=", "spring.datasource.url=jdbc:h2:mem:profiles;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class ProfileApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RoomStore store;

    byte[] photo() throws Exception { return getClass().getResourceAsStream("/avatar.jpg").readAllBytes(); }
    String dataUrl() throws Exception { return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(photo()); }
    JsonNode create(String avatar, String visibility) throws Exception {
        var body=json.createObjectNode().put("keyword","과학").put("name","사진 방장").put("count",4).put("capacity",4)
                .put("avatar",avatar).put("visibility",visibility);
        return json.readTree(mvc.perform(post("/api/rooms").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
    }
    JsonNode state(JsonNode session) throws Exception {
        return json.readTree(mvc.perform(get("/api/rooms/"+session.get("code").asText()+"/state")
                .header("Authorization","Bearer "+session.get("token").asText())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
    }

    @Test void configExposesCapabilitiesWithoutAnApiKey() throws Exception {
        String body=mvc.perform(get("/api/config")).andExpect(status().isOk()).andExpect(jsonPath("$.keywordMaxLength").value(30))
                .andExpect(jsonPath("$.profiles").value(true)).andExpect(jsonPath("$.automaticSource").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("apiKey", "api-key");
    }

    @Test void photosAreSharedByHashAndNotRepeatedInTheState() throws Exception {
        JsonNode host=create(dataUrl(),"public");String code=host.get("code").asText();
        mvc.perform(post("/api/rooms/"+code+"/join").contentType(MediaType.APPLICATION_JSON)
                .content(json.createObjectNode().put("name","사진 친구").put("avatar",dataUrl()).toString())).andExpect(status().isOk());
        JsonNode state=state(host);String url=state.get("players").get(0).get("avatar").asText();
        assertThat(url).matches("/api/avatars/[a-f0-9]{64}\\.jpg");
        assertThat(state.get("players").get(1).get("avatar").asText()).isEqualTo(url);
        assertThat(state.toString()).doesNotContain("data:image",host.get("token").asText());
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string("X-Content-Type-Options","nosniff")).andExpect(content().bytes(photo()));
        mvc.perform(get("/api/avatars/"+"0".repeat(64)+".jpg")).andExpect(status().isNotFound());
    }

    @Test void authenticatedProfileUpdatesKeepScoresAndRejectInvalidChangesAtomically() throws Exception {
        JsonNode host=create(dataUrl(),"public");String code=host.get("code").asText(),token=host.get("token").asText();
        Room room=store.find(code).orElseThrow();room.players.get(room.host).score=1234;room.players.get(room.host).correct=1;store.save(room);
        String path="/api/rooms/"+code+"/profile";
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.createObjectNode().put("token","wrong").put("name","침입자").put("avatar","").toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.createObjectNode().put("token",token).put("name","새 이름").put("avatar","").toString()))
                .andExpect(status().isOk());
        JsonNode changed=state(host).get("players").get(0);assertThat(changed.get("name").asText()).isEqualTo("새 이름");
        assertThat(changed.get("avatar").asText()).isEmpty();assertThat(changed.get("score").asInt()).isEqualTo(1234);assertThat(changed.get("correct").asInt()).isEqualTo(1);
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.createObjectNode().put("token",token).put("name","잘못된 이름").put("avatar","https://example.com/photo.jpg").toString()))
                .andExpect(status().isBadRequest());
        assertThat(state(host).get("players").get(0).get("name").asText()).isEqualTo("새 이름");
    }

    @Test void invitationRoomsAreHiddenFromTheLobbyAndStillJoinable() throws Exception {
        JsonNode privateRoom=create("","friends"),publicRoom=create("","public");
        String list=mvc.perform(get("/api/rooms")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain(privateRoom.get("code").asText()).contains(publicRoom.get("code").asText());
        mvc.perform(post("/api/rooms/"+privateRoom.get("code").asText()+"/join").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"초대 친구\"}"))
                .andExpect(status().isOk());
        assertThat(state(privateRoom).get("visibility").asText()).isEqualTo("friends");
    }
}
