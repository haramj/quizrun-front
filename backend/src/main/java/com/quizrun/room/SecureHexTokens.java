package com.quizrun.room;

import java.security.SecureRandom;
import java.util.HexFormat;

/** 방 코드(6자리)와 참가자 토큰(48자리)을 만드는 암호학적 난수 헥스 문자열. */
final class SecureHexTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecureHexTokens() {}

    static String hex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
