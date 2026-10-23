package com.quizrun;

import java.time.Clock;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// Redis 자동 설정은 끈다: 방 저장소를 redis로 고른 경우(RedisRoomConfig)에만 연결을 만든다.
// (메모리 모드에서 localhost:6379에 접속을 시도하거나 헬스체크가 DOWN이 되는 것을 막는다)
@SpringBootApplication(exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
@EnableScheduling
public class QuizrunApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuizrunApplication.class, args);
    }

    /** 시간 소스를 빈으로 분리해 테스트에서 시각을 직접 조작할 수 있게 한다. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * 문제 준비(AI 생성)를 요청 스레드 밖에서 돌리는 실행기. 스레드 수가 동시 생성 상한이고,
     * 대기열이 가득 차면 TaskRejectedException이 나서 방이 "요청이 많아요" 실패로 표시된다(서버 보호).
     */
    @Bean(name = "quizPreparer")
    Executor quizPreparer(@Value("${quizrun.prepare.threads:4}") int threads,
                          @Value("${quizrun.prepare.queue:50}") int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix("prepare-");
        executor.setDaemon(true);
        executor.initialize();
        return executor;
    }
}
