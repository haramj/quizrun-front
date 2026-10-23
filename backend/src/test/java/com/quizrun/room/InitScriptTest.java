package com.quizrun.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * NCP Init Script 규칙(수업 Day2)을 코드로 고정한다: 내용은 영어만(한글·주석 불가), Linux는 첫 줄 shebang 필수.
 * 이 규칙을 어기면 서버 생성 때 스크립트가 조용히 실패하는 것이 실습에서 가장 흔한 실수였다.
 */
class InitScriptTest {

    static final Path SCRIPT = Path.of("deploy/vm/init-script.sh");

    @Test
    void initScriptFollowsTheNcpRules() throws Exception {
        String text = Files.readString(SCRIPT);
        List<String> lines = text.lines().toList();

        assertThat(lines.get(0)).as("첫 줄은 shebang").startsWith("#!");
        assertThat(text.chars().allMatch(c -> c < 128)).as("ASCII(영어)만 — 한글 불가").isTrue();
        assertThat(lines.stream().skip(1).filter(l -> l.stripLeading().startsWith("#")).toList())
                .as("주석 없음 — NCP 공식 규칙은 영어 주석도 넣지 않는 것").isEmpty();
    }

    @Test
    void everyPlaceholderIsDocumentedInTheEnvExample() throws Exception {
        String text = Files.readString(SCRIPT);
        Matcher m = Pattern.compile("__([A-Z_]+)__").matcher(text);
        String example = Files.readString(Path.of(".env.example")) + Files.readString(Path.of("docs/deploy-vm.md"));
        while (m.find()) {
            assertThat(example).as("자리표시자 " + m.group(1) + "의 설명이 문서에 있어야 한다").contains(m.group(1));
        }
    }

    @Test
    void theSystemdUnitStartsOnlyAfterTheInitScriptWroteTheSettings() throws Exception {
        String unit = Files.readString(Path.of("deploy/vm/quizrun.service"));
        assertThat(unit).contains("ConditionFileNotEmpty=/etc/quizrun/quizrun.env")
                .contains("EnvironmentFile=/etc/quizrun/quizrun.env")
                .contains("SuccessExitStatus=143")
                .contains("Restart=on-failure");
    }

    @Test
    void theInitScriptWritesTheSameFileTheUnitReads() throws Exception {
        String script = Files.readString(SCRIPT);
        assertThat(script).contains("/etc/quizrun/quizrun.env").contains("SPRING_PROFILES_ACTIVE=redis")
                .contains("systemctl restart quizrun");
    }
}
