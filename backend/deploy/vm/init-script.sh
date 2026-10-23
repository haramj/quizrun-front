#!/bin/bash
set -e
cat > /etc/quizrun/quizrun.env <<'ENVEOF'
SPRING_PROFILES_ACTIVE=redis
PORT=3000
REDIS_HOST=__REDIS_HOST__
REDIS_PORT=6379
REDIS_PASSWORD=__REDIS_PASSWORD__
DB_URL=jdbc:mysql://__DB_HOST__:3306/quizrun?characterEncoding=UTF-8&serverTimezone=UTC
DB_USER=__DB_USER__
DB_PASSWORD=__DB_PASSWORD__
NCP_CLOVA_API_KEY=__NCP_CLOVA_API_KEY__
ADMIN_TOKEN=__ADMIN_TOKEN__
ENVEOF
chown root:quizrun /etc/quizrun/quizrun.env
chmod 640 /etc/quizrun/quizrun.env
systemctl daemon-reload
systemctl restart quizrun
