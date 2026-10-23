package com.quizrun.room;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class AvatarServiceTest {
    String photo() throws Exception { return "data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(getClass().getResourceAsStream("/avatar.jpg").readAllBytes()); }

    @Test void invalidImagesNeverEnterThePhotoStore() throws Exception {
        AvatarService service=new AvatarService(new MutableClock());
        for(String value:new String[]{"https://example.com/a.jpg","data:image/svg+xml;base64,PHN2Zz4=","data:image/jpeg;base64,ZmFrZQ==","x".repeat(66001)})
            assertThatThrownBy(()->service.parse(value)).isInstanceOf(ApiException.class);
        byte[] tooWide=Base64.getDecoder().decode(photo().split(",")[1]);
        for(int i=2;i+9<tooWide.length;i++)if(Byte.toUnsignedInt(tooWide[i])==255&&Byte.toUnsignedInt(tooWide[i+1])==192){tooWide[i+7]=2;tooWide[i+8]=0;break;}
        assertThatThrownBy(()->service.parse("data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(tooWide))).isInstanceOf(ApiException.class);
        assertThat(service.parse("")).isNull();
    }

    @Test void memoryPhotosExpireAndDoNotExposeMutableBytes() throws Exception {
        MutableClock clock=new MutableClock();AvatarService service=new AvatarService(clock,Duration.ofSeconds(10),null);
        var photo=service.parse(photo());service.save(photo);byte[] found=service.find(photo.id()).orElseThrow();found[0]=0;
        assertThat(service.find(photo.id()).orElseThrow()[0]).isEqualTo((byte)255);
        clock.advance(10001);assertThat(service.find(photo.id())).isEmpty();
    }

    @Test @SuppressWarnings("unchecked") void redisPhotosCanBeReadByAnotherApiServer() throws Exception {
        StringRedisTemplate redis=mock(StringRedisTemplate.class);ValueOperations<String,String> values=mock(ValueOperations.class);
        Map<String,String> shared=new ConcurrentHashMap<>();when(redis.opsForValue()).thenReturn(values);
        doAnswer(call->{shared.put(call.getArgument(0),call.getArgument(1));return null;}).when(values).set(anyString(),anyString(),any(Duration.class));
        when(values.get(anyString())).thenAnswer(call->shared.get(call.getArgument(0)));
        MutableClock clock=new MutableClock();AvatarService a=new AvatarService(clock,Duration.ofHours(7),redis),b=new AvatarService(clock,Duration.ofHours(7),redis);
        var photo=a.parse(photo());String url=a.save(photo);assertThat(url).contains(photo.id());assertThat(b.find(photo.id()).orElseThrow()).isEqualTo(photo.bytes());
        verify(values).set(eq("quizrun:avatar:"+photo.id()),anyString(),eq(Duration.ofHours(7)));
    }
}
