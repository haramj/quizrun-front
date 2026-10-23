#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""init-script.sh 의 __자리표시자__ 를 실제 값으로 채워 표준출력으로 내보낸다.

사용:  python3 deploy/vm/render-init-script.py prod.env > /tmp/init-script.filled.sh
       (NCP 콘솔 > Server > Init Script 에 /tmp/init-script.filled.sh 내용을 붙여 넣는다)

prod.env 예:  REDIS_HOST=10.0.3.10 ... (키 목록은 init-script.sh 의 __키__ 와 같다. 값에 공백·& 가 있어도 된다)
주의: 출력에는 비밀번호·키가 들어 있다. 파일을 Git에 올리거나 채팅에 붙이지 말 것. 값에 줄바꿈이 있으면 거부한다.
"""
import io, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))

# 비워 둘 수 있는 값: Redis 비밀번호(없는 Redis), CLOVA 키(없으면 AI 생성만 꺼진다), 관리자 토큰(없으면 관리자 API가 꺼진다)
OPTIONAL = {"REDIS_PASSWORD", "NCP_CLOVA_API_KEY", "ADMIN_TOKEN"}


def load_env(path):
    values = {}
    for n, line in enumerate(io.open(path, encoding="utf-8"), 1):
        line = line.rstrip("\n")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if "=" not in line:
            sys.exit("%s:%d: KEY=VALUE 형식이 아닙니다" % (path, n))
        k, v = line.split("=", 1)
        values[k.strip()] = v
    return values


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    template = io.open(os.path.join(HERE, "init-script.sh"), encoding="utf-8").read()
    values = load_env(sys.argv[1])
    needed = sorted(set(re.findall(r"__([A-Z_]+)__", template)))
    missing = [k for k in needed if k not in OPTIONAL and not values.get(k)]
    absent = [k for k in needed if k not in values]
    if absent:
        sys.exit("키가 없는 항목(비워 두려면 KEY= 로 적으세요): " + ", ".join(absent))
    if missing:
        sys.exit("값이 없는 항목: " + ", ".join(missing))
    for k in needed:
        v = values.get(k, "")
        if "\n" in v or "\r" in v:
            sys.exit(k + ": 줄바꿈이 있는 값은 쓸 수 없습니다")
        template = template.replace("__%s__" % k, v)
    try:
        template.encode("ascii")
    except UnicodeEncodeError:
        sys.exit("결과에 ASCII가 아닌 문자가 있습니다. Init Script는 영어만 허용됩니다(수업 Day2). 값을 확인하세요.")
    sys.stdout.write(template)


if __name__ == "__main__":
    main()
