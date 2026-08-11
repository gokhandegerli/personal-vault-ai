#!/usr/bin/env bash
# personal-vault-ai one-command startup (after reboot).
# Usage: ./start.sh   (or alias pva='.../start.sh')
set -euo pipefail

cd "$(dirname "$0")"
PROJECT_DIR="$(pwd)"
APP_LOG="/tmp/pva-boot.log"

# Ensure sdkman Java is available in non-interactive shells
if ! command -v mvn >/dev/null 2>&1 && [ -s "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
    # shellcheck disable=SC1091
    . "$HOME/.sdkman/bin/sdkman-init.sh"
fi

echo "[1/4] Ollama"
if ! pgrep -x ollama >/dev/null 2>&1; then
    nohup ollama serve >/tmp/ollama.log 2>&1 &
fi
until curl -sf http://localhost:11434/api/version >/dev/null 2>&1; do sleep 1; done
echo "      ollama ready"

echo "[2/4] Docker stores (pgvector + chroma)"
docker compose up -d
until [ "$(docker inspect -f '{{.State.Health.Status}}' pva-pgvector 2>/dev/null)" = "healthy" ]; do sleep 2; done
echo "      stores ready"

echo "[3/4] Application (tmux session: pva)"
tmux kill-session -t pva 2>/dev/null || true
tmux new-session -d -s pva -c "$PROJECT_DIR" \
    "mvn -q spring-boot:run -Ppgvector -Dspring-boot.run.profiles=pgvector,zen > $APP_LOG 2>&1"

echo "[4/4] Waiting for HTTP 200"
until curl -sf -o /dev/null http://localhost:8080/api/conversations; do sleep 2; done

echo
echo "PVA is UP: http://localhost:8080"
echo "  log: tail -f $APP_LOG"
echo "  tmux: tmux attach -t pva   (detach: Ctrl+B, D)"
