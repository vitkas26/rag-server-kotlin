#!/usr/bin/env bash
# Деплой rag-day21 на VPS через SSH+git: stop -> pull -> build -> run -> healthcheck.
# set -e — если сборка на VPS упадёт, скрипт остановится и не попытается запустить
# сломанный код (шаг "г" ниже просто не выполнится).
set -e

VPS_IP="138.16.155.105"
VPS_USER="root"
VPS_PATH="/opt/rag-day21"
BRANCH="ai_advent_day29_local_llm_optimization"
OLLAMA_MODEL="qwen2.5:7b-instruct-q4_0"

SSH="ssh ${VPS_USER}@${VPS_IP}"
LOG_FILE="/tmp/rag-day21.log"

echo "=== [1/6] Останавливаю текущий сервер на VPS ==="
$SSH "pkill -f MainKt || true; pkill -f gradlew || true"

echo "=== [2/6] git pull ветки ${BRANCH} на VPS ==="
$SSH "cd ${VPS_PATH} && git fetch origin && git checkout ${BRANCH} && git pull origin ${BRANCH}"

echo "=== [3/6] Сборка проекта на VPS (./gradlew build -x test) ==="
$SSH "cd ${VPS_PATH} && ./gradlew build -x test"

echo "=== [4/6] Запускаю сервер в фоне (OLLAMA_GENERATION_MODEL=${OLLAMA_MODEL}) ==="
# rag_index.db не трогаем — индекс должен переживать деплой, пересобирается вручную отдельно.
# nohup + перенаправление stdin/stdout/stderr — чтобы процесс пережил закрытие ssh-сессии.
$SSH "cd ${VPS_PATH} && rm -f ${LOG_FILE} && OLLAMA_GENERATION_MODEL='${OLLAMA_MODEL}' nohup ./gradlew run > ${LOG_FILE} 2>&1 < /dev/null & disown; sleep 1; echo 'server launch triggered'"

echo "=== [5/6] Жду старта и проверяю health-check ==="
sleep 10
# Отдельного health-check роута в проекте нет — /ask-local защищён Basic Auth, поэтому
# без креденшлов ожидаем 401 (это ОК: значит сервер поднялся и роутинг/auth отвечают).
# 000 или пусто — сервер не поднялся, это провал деплоя.
HTTP_STATUS=$($SSH "curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://localhost:8080/ask-local -X POST -H 'Content-Type: application/json' -d '{\"question\":\"healthcheck\"}'" || echo "000")

if [ "$HTTP_STATUS" = "000" ] || [ -z "$HTTP_STATUS" ]; then
    echo "!!! Health-check провалился: сервер не отвечает на :8080 (status=$HTTP_STATUS)"
    echo "!!! Смотри логи: ssh ${VPS_USER}@${VPS_IP} \"tail -100 ${LOG_FILE}\""
    exit 1
fi

echo "Health-check: HTTP $HTTP_STATUS от /ask-local (401 без креденшлов — это ожидаемо и означает, что сервер жив)"

echo "=== [6/6] Деплой завершён ==="
echo "Смотреть логи в реальном времени:"
echo "  ssh ${VPS_USER}@${VPS_IP} \"tail -f ${LOG_FILE}\""
