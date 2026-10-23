#!/bin/bash
# 골든 서버(서버 이미지의 원본이 될 서버)에서 root로 한 번 실행한다. SSH 접속 후:
#   sudo bash install-app.sh /home/<user>/quizrun-back-0.3.0.jar
# 하는 일: Java 21 설치, 서비스 계정 생성, jar 배치, systemd 서비스 등록.
# 서비스는 "등록만" 하고 시작하지 않는다 — DB·Redis 주소가 담긴 설정 파일은 서버가 만들어질 때 Init Script가 쓴다.
# 이 파일은 SSH로 직접 실행하므로 한글 주석을 써도 된다(Init Script와 달리).
set -euo pipefail

JAR_SRC="${1:?사용법: install-app.sh <jar 경로>}"
[ -f "$JAR_SRC" ] || { echo "jar 파일이 없습니다: $JAR_SRC" >&2; exit 1; }
HERE="$(cd "$(dirname "$0")" && pwd)"

dnf install -y java-21-openjdk-headless curl

id quizrun >/dev/null 2>&1 || useradd --system --home-dir /opt/quizrun --shell /sbin/nologin quizrun
install -d -o quizrun -g quizrun -m 750 /opt/quizrun
install -d -o root -g quizrun -m 750 /etc/quizrun
install -o quizrun -g quizrun -m 640 "$JAR_SRC" /opt/quizrun/app.jar
install -m 644 "$HERE/quizrun.service" /etc/systemd/system/quizrun.service
# 빈 설정 파일: 이미지에 비밀값이 들어가지 않게 한다.
: > /etc/quizrun/quizrun.env
chown root:quizrun /etc/quizrun/quizrun.env
chmod 640 /etc/quizrun/quizrun.env

systemctl daemon-reload
systemctl enable quizrun

java -version 2>&1 | head -1
echo "설치 완료. 이 서버로 서버 이미지를 만들어도 됩니다(설정 파일이 비어 있어 비밀값이 이미지에 들어가지 않는다)."
