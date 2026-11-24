#!/usr/bin/env bash
# Rompe a propósito una garantía y verifica que la prueba que la protege FALLE.
# Si la prueba pasa con la garantía rota, el gate es defectuoso y este script termina en error.
#   bash scripts/sabotaje.sh <id>
set -uo pipefail
cd "$(dirname "$0")/.."
SQL=src/main/resources/db/migration/V1__esquema.sql
STATUS=src/main/java/co/mzarta/pagos/domain/PaymentStatus.java
case "$1" in
  S1) desc="Se quita la unicidad del inbox (AC-04)"
      sed -i 's/CONSTRAINT uq_inbox_event UNIQUE (provider, provider_event_id)/CONSTRAINT uq_inbox_event CHECK (true)/' "$SQL"
      test="IntegridadTest#ac04*" ;;
  S2) desc="Se quita el índice de un intento activo por orden (AC-02)"
      sed -i 's/CREATE UNIQUE INDEX uq_one_active_attempt/CREATE INDEX uq_one_active_attempt/' "$SQL"
      test="IntegridadTest#ac02*" ;;
  S3) desc="Se permite que un aprobado vuelva a PENDING (monotonía)"
      sed -i 's/case PENDING -> EnumSet.of(CREATED, UNKNOWN);/case PENDING -> EnumSet.of(CREATED, UNKNOWN, APPROVED);/' "$STATUS"
      test="PaymentStatusTest" ;;
  S4) desc="La API acepta un segundo intento aunque la clave ya exista (AC-01)"
      sed -i 's/ON CONFLICT (idempotency_key) DO NOTHING/ON CONFLICT DO NOTHING/; s/Optional<AttemptRow> replay = findByKey(idempotencyKey);/Optional<AttemptRow> replay = Optional.empty();/' src/main/java/co/mzarta/pagos/api/CheckoutService.java
      test="IntegridadTest#ac01*" ;;
  *) echo "uso: sabotaje.sh S1|S2|S3|S4"; exit 2 ;;
esac
if git diff --quiet; then echo "El sabotaje $1 no cambió nada: el script quedó desactualizado"; exit 1; fi
echo "## Sabotaje $1: $desc"; git diff --stat
mvn -B -q test -Dtest="$test" -Dsurefire.failIfNoSpecifiedTests=false > /tmp/sabotaje.log 2>&1; rc=$?
grep -E "Tests run:|FAIL|expected|Expecting" /tmp/sabotaje.log | head -8
git checkout -- . 
if [ $rc -eq 0 ]; then echo "RESULTADO: el gate NO detectó el sabotaje $1 (defecto del gate)"; exit 1; fi
echo "RESULTADO: el gate detectó el sabotaje $1 (la prueba falló, como debe)"
