package com.quizrun.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizrun.room.Dtos.CreateRoomRequest;
import com.quizrun.room.Dtos.JoinResponse;
import com.quizrun.room.Dtos.RoomState;

/**
 * 진짜 Redis에 대한 통합 테스트. 환경변수 REDIS_TEST_HOST가 있을 때만 실행된다(없으면 건너뜀).
 *
 * <pre>
 *   docker network create qr-net && docker run -d --name qr-redis --network qr-net redis:7
 *   docker run --rm --network qr-net -e REDIS_TEST_HOST=qr-redis -v "$PWD":/app -w /app gradle:8.10-jdk21 \
 *       gradle test --tests '*RedisRoomStoreIT' --no-daemon
 * </pre>
 * 주의: 테스트 시작 시 해당 Redis의 현재 DB를 비운다(flushDb). 운영 Redis를 가리키지 말 것.
 */
@EnabledIfEnvironmentVariable(named = "REDIS_TEST_HOST", matches = ".+")
class RedisRoomStoreIT {

    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;

    RedisRoomStore store;
    RedisRoomLocks locks;
    ExecutorService pool;

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(
                System.getenv("REDIS_TEST_HOST"), Integer.parseInt(System.getenv().getOrDefault("REDIS_TEST_PORT", "6379")));
        factory = new LettuceConnectionFactory(cfg);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void close() {
        factory.destroy();
    }

    @BeforeEach
    void setUp() {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) c -> {
            c.serverCommands().flushDb();
            return null;
        });
        store = new RedisRoomStore(redis, Clock.systemUTC(), Duration.ofHours(6).toMillis());
        locks = new RedisRoomLocks(redis, Duration.ofSeconds(10), Duration.ofSeconds(5));
        pool = Executors.newFixedThreadPool(16);
    }

    Room room(String code) {
        Room r = RoomCodecTest.richRoom();
        // 코드만 바꾼 복사본
        Room copy = RoomCodec.decode(RoomCodec.encode(r).replace("ABC123", code));
        return copy;
    }

    @Test
    void roomSurvivesARoundTripThroughRealRedisAndFindReturnsASnapshot() {
        Room original = room("RT0001");
        assertThat(store.saveIfAbsent(original)).isTrue();
        Room loaded = store.find("RT0001").orElseThrow();
        assertThat(loaded).usingRecursiveComparison().isEqualTo(original);

        loaded.players.get("p1").score = 99999;                       // 복사본을 고쳐도
        assertThat(store.find("RT0001").orElseThrow().players.get("p1").score).as("save 전에는 저장소에 영향 없음").isEqualTo(2750);
        store.save(loaded);
        assertThat(store.find("RT0001").orElseThrow().players.get("p1").score).isEqualTo(99999);
    }

    @Test
    void saveIfAbsentIsAtomicAcrossManyThreads() throws Exception {
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 24; i++) tasks.add(() -> store.saveIfAbsent(room("DUP001")));
        long winners = run(tasks).stream().filter(b -> b).count();
        assertThat(winners).as("같은 코드는 한 서버만 만들 수 있다").isEqualTo(1);
        assertThat(redis.opsForSet().members(RedisRoomStore.INDEX)).containsExactly("DUP001");
    }

    @Test
    void saveDoesNotResurrectADeletedRoom() {
        store.saveIfAbsent(room("DEL001"));
        Room stale = store.find("DEL001").orElseThrow();
        store.delete("DEL001");
        store.save(stale);
        assertThat(store.exists("DEL001")).isFalse();
        assertThat(store.find("DEL001")).isEmpty();
    }

    @Test
    void roomKeysExpireOnTheirOwnSoDeadServersCannotLeakRooms() {
        // 방금 만든 방: 6시간 TTL
        Room fresh = new Room("TTL001", "t", "k", 2, 10, 4, System.currentTimeMillis());
        assertThat(store.saveIfAbsent(fresh)).isTrue();
        assertThat(redis.getExpire(RedisRoomStore.ROOM + "TTL001", TimeUnit.SECONDS))
                .isBetween(Duration.ofHours(6).toSeconds() - 60, Duration.ofHours(6).toSeconds());

        // 이미 TTL이 지난 방(생성 시각이 아주 오래전): 진행 중인 요청이 갑자기 실패하지 않게 최소 1분은 남긴다
        store.saveIfAbsent(room("TTL002"));
        assertThat(redis.getExpire(RedisRoomStore.ROOM + "TTL002", TimeUnit.SECONDS)).isBetween(50L, 60L);
    }

    @Test
    void listingDropsRoomsWhoseKeysExpiredAndCleansTheIndex() {
        store.saveIfAbsent(room("LST001"));
        store.saveIfAbsent(room("LST002"));
        redis.delete(RedisRoomStore.ROOM + "LST001");                  // TTL 만료를 흉내
        assertThat(store.all()).extracting(r -> r.code).containsExactly("LST002");
        assertThat(redis.opsForSet().members(RedisRoomStore.INDEX)).containsExactly("LST002");
    }

    @Test
    void presenceIsWrittenWithoutTouchingTheRoomAndExpires() {
        store.saveIfAbsent(room("PRS001"));
        String roomBefore = redis.opsForValue().get(RedisRoomStore.ROOM + "PRS001");
        store.touch("PRS001", "p1", 12345L);
        store.touch("PRS001", "p2", 67890L);
        assertThat(store.presence("PRS001")).containsEntry("p1", 12345L).containsEntry("p2", 67890L);
        assertThat(redis.opsForValue().get(RedisRoomStore.ROOM + "PRS001")).as("방 본문은 그대로").isEqualTo(roomBefore);
        assertThat(redis.getExpire(RedisRoomStore.PRESENCE + "PRS001", TimeUnit.SECONDS)).isPositive();
    }

    @Test
    void theLockGivesMutualExclusionToAReadModifyWriteOnRealRedis() throws Exception {
        redis.opsForValue().set("test:counter", "0");
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            tasks.add(() -> {
                for (int i = 0; i < 60; i++) {
                    locks.run("CNT001", () -> {                          // 락이 없으면 갱신이 유실된다
                        int v = Integer.parseInt(redis.opsForValue().get("test:counter"));
                        redis.opsForValue().set("test:counter", Integer.toString(v + 1));
                        return null;
                    });
                }
                return null;
            });
        }
        run(tasks);
        assertThat(redis.opsForValue().get("test:counter")).isEqualTo("480");
    }

    @Test
    void theLockIsReleasedEvenWhenTheTaskFailsAndOnlyByItsOwner() {
        assertThatThrownBy(() -> locks.run("ERR001", () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(redis.hasKey(RedisRoomLocks.PREFIX + "ERR001")).as("실패해도 락이 풀린다").isFalse();

        // 남의 락은 지우지 않는다: 내 락이 만료되고 다른 서버가 잡았다면, 늦게 끝난 내가 그 락을 지우면 안 된다
        RedisRoomLocks shortLease = new RedisRoomLocks(redis, Duration.ofMillis(200), Duration.ofSeconds(2));
        shortLease.run("OWN001", () -> {
            sleep(400);                                                  // 내 락이 만료된다
            redis.opsForValue().set(RedisRoomLocks.PREFIX + "OWN001", "someone-else", Duration.ofSeconds(5));
            return null;
        });
        assertThat(redis.opsForValue().get(RedisRoomLocks.PREFIX + "OWN001")).isEqualTo("someone-else");
    }

    @Test
    void aLockLeftByACrashedServerExpiresAndTheNextRequestProceeds() {
        redis.opsForValue().set(RedisRoomLocks.PREFIX + "CRS001", "dead-server", Duration.ofMillis(300));
        RedisRoomLocks l = new RedisRoomLocks(redis, Duration.ofSeconds(10), Duration.ofSeconds(3));
        long t0 = System.currentTimeMillis();
        assertThat(l.run("CRS001", () -> "done")).isEqualTo("done");
        assertThat(System.currentTimeMillis() - t0).as("락 만료를 기다린 뒤 진행").isBetween(200L, 2500L);
    }

    @Test
    void waitingTooLongForALockFailsFastWith503() {
        redis.opsForValue().set(RedisRoomLocks.PREFIX + "BSY001", "someone", Duration.ofSeconds(30));
        RedisRoomLocks impatient = new RedisRoomLocks(redis, Duration.ofSeconds(10), Duration.ofMillis(300));
        assertThatThrownBy(() -> impatient.run("BSY001", () -> "x"))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).status().value()).isEqualTo(503);
    }

    @Test
    void twoGameServersOverRealRedisPlayARaceTogether() throws Exception {
        MutableClock clock = new MutableClock();
        InMemoryQuestionVault vault = new InMemoryQuestionVault();
        QuestionBank bank = new QuestionBank(new ObjectMapper());
        new QuestionSeeder(vault, bank).run(null);
        FakeGenerator gen = new FakeGenerator();
        QuizComposer composer = new QuizComposer(vault, new FakeSources(), gen, bank, 0.3);
        RedisRoomStore redisStore = new RedisRoomStore(redis, clock, Duration.ofHours(6).toMillis());
        RoomService a = new RoomService(redisStore, locks, composer, new QuestionReportService(vault, 3), clock, Runnable::run);
        RoomService b = new RoomService(redisStore, locks, composer, new QuestionReportService(vault, 3), clock, Runnable::run);
        clock.advance(0);

        JoinResponse host = a.create(new CreateRoomRequest("개발", null, "Host", 2, 10, 4));
        JoinResponse guest = b.join(host.code(), "Friend");
        assertThat(a.state(host.code(), host.token()).players()).hasSize(2);

        clock.advance(5000);
        int correct = redisStore.find(host.code()).orElseThrow().questions.get(0).answer();
        a.answer(host.code(), host.token(), correct);
        b.answer(host.code(), guest.token(), correct);
        RoomState s = b.state(host.code(), host.token());
        assertThat(s.phase()).isEqualTo("reveal");
        assertThat(s.players()).allMatch(p -> p.score() == 1500);
    }

    // ---- 도우미 ----
    <T> List<T> run(List<Callable<T>> tasks) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> fs = new ArrayList<>();
        for (Callable<T> t : tasks) fs.add(pool.submit(() -> { go.await(); return t.call(); }));
        go.countDown();
        List<T> out = new ArrayList<>();
        for (Future<T> f : fs) out.add(f.get(30, TimeUnit.SECONDS));
        return out;
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
