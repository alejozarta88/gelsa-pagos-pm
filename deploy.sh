#!/usr/bin/env bash
# Despliegue en el VPS en un solo comando. Idempotente: se puede correr de nuevo para actualizar.
#   bash deploy.sh
set -euo pipefail
cd "$(dirname "$0")"
if ! command -v docker >/dev/null; then
  echo "Instalando Docker..."; curl -fsSL https://get.docker.com | sh
fi
if [ ! -f .env ]; then
  cp .env.example .env
  sed -i "s/^DB_PASSWORD=.*/DB_PASSWORD=$(openssl rand -hex 24)/" .env
  sed -i "s/^PSP_WEBHOOK_SECRET=.*/PSP_WEBHOOK_SECRET=$(openssl rand -hex 32)/" .env
  echo ".env creado con secretos aleatorios. Falta pegar CF_TUNNEL_TOKEN si se usará el túnel."
fi
git pull --ff-only || true
PROFILE=""
grep -q '^CF_TUNNEL_TOKEN=.\+' .env && PROFILE="--profile tunnel"
docker compose $PROFILE up -d --build
echo "Esperando a la aplicación..."
for i in $(seq 1 60); do
  if curl -fs http://127.0.0.1:8085/actuator/health >/dev/null; then echo "OK: http://127.0.0.1:8085"; exit 0; fi
  sleep 3
done
echo "La aplicación no respondió. Revisar: docker compose logs app --tail 100"; exit 1
