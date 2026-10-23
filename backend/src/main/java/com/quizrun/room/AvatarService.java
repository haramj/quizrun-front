package com.quizrun.room;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Photos stay out of room JSON and polling. Redis mode shares the bytes across API servers. */
@Service
public class AvatarService {
    private static final String PREFIX = "data:image/jpeg;base64,";
    private static final String KEY = "quizrun:avatar:";
    private final Clock clock;
    private final Duration ttl;
    private final StringRedisTemplate redis;
    private final Map<String, Entry> memory = new ConcurrentHashMap<>();
    private record Entry(byte[] bytes, long expires) {}
    public record Photo(String id, byte[] bytes) {}

    @Autowired
    public AvatarService(Clock clock, @Value("${quizrun.room-ttl-minutes:360}") long ttlMinutes,
                         ObjectProvider<StringRedisTemplate> redis) {
        this(clock, Duration.ofMinutes(ttlMinutes + 10), redis.getIfAvailable());
    }

    AvatarService(Clock clock) { this(clock, Duration.ofHours(7), null); }
    AvatarService(Clock clock, Duration ttl, StringRedisTemplate redis) {
        this.clock = clock; this.ttl = ttl; this.redis = redis;
    }

    public Photo parse(String input) {
        if (input == null || input.isEmpty()) return null;
        if (input.length() > 66000 || !input.startsWith(PREFIX)
                || !input.substring(PREFIX.length()).matches("[A-Za-z0-9+/]+={0,2}")) throw invalid();
        try {
            byte[] bytes = Base64.getDecoder().decode(input.substring(PREFIX.length()));
            if (bytes.length < 12 || bytes.length > 48 * 1024 || u(bytes[0]) != 255 || u(bytes[1]) != 216
                    || u(bytes[bytes.length-2]) != 255 || u(bytes[bytes.length-1]) != 217) throw invalid();
            boolean bounded = false;
            for (int offset = 2; offset + 4 < bytes.length;) {
                if (u(bytes[offset]) != 255) break;
                int marker = u(bytes[offset+1]), length = word(bytes, offset+2);
                if (marker == 218 || marker == 217 || length < 2 || offset + 2 + length > bytes.length) break;
                if ((marker == 192 || marker == 193 || marker == 194) && length >= 8) {
                    int height = word(bytes, offset+5), width = word(bytes, offset+7);
                    bounded = width > 0 && height > 0 && width <= 256 && height <= 256;
                    break;
                }
                offset += 2 + length;
            }
            if (!bounded) throw invalid();
            var image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() > 256 || image.getHeight() > 256) throw invalid();
            String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new Photo(id, bytes);
        } catch (ApiException error) { throw error; }
        catch (Exception error) { throw invalid(); }
    }

    public String save(Photo photo) {
        if (photo == null) return "";
        if (redis != null) redis.opsForValue().set(KEY + photo.id(), Base64.getEncoder().encodeToString(photo.bytes()), ttl);
        else memory.put(photo.id(), new Entry(photo.bytes().clone(), clock.millis() + ttl.toMillis()));
        return "/api/avatars/" + photo.id() + ".jpg";
    }

    public Optional<byte[]> find(String id) {
        if (!id.matches("[a-f0-9]{64}")) return Optional.empty();
        if (redis != null) return Optional.ofNullable(redis.opsForValue().get(KEY + id)).map(Base64.getDecoder()::decode);
        Entry entry = memory.get(id);
        if (entry == null) return Optional.empty();
        if (entry.expires() <= clock.millis()) { memory.remove(id, entry); return Optional.empty(); }
        return Optional.of(entry.bytes().clone());
    }

    @Scheduled(fixedDelay = 60000)
    void cleanup() { memory.entrySet().removeIf(entry -> entry.getValue().expires() <= clock.millis()); }
    private static int u(byte value) { return Byte.toUnsignedInt(value); }
    private static int word(byte[] bytes, int offset) { return u(bytes[offset]) * 256 + u(bytes[offset+1]); }
    private static ApiException invalid() { return ApiException.badRequest("프로필 사진을 다시 선택해 주세요. 256px 이하, 48KB 이하의 JPG만 사용할 수 있어요."); }
}
