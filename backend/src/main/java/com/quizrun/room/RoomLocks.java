package com.quizrun.room;

import java.util.function.Supplier;

/**
 * 방 단위 상호 배제. 서버가 여러 대여도 한 방의 "읽기 → 수정 → 쓰기"가 동시에 둘 이상 일어나지 않게 한다.
 * 구현체: 메모리(서버 1대), Redis 분산 락(서버 여러 대).
 * 재진입을 가정하지 않는다: 락 안에서 같은 방의 락을 다시 잡지 말 것.
 */
public interface RoomLocks {

    <T> T run(String code, Supplier<T> task);
}
