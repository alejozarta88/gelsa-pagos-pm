# Deuda técnica

Ordenada por severidad. Cada entrada dice qué falta, qué lo contiene hoy y cuándo se considera cerrada.

## Alta

**DT-01 · Un solo nodo: workers y conciliador sin coordinación entre instancias.**
Hoy: `SKIP LOCKED` ya evita que dos workers tomen el mismo evento, pero el conciliador corre en cada instancia.
Plan: ShedLock sobre la misma base. Cierre: dos instancias en paralelo sin consultas duplicadas al proveedor.

**DT-10 · AC-03, AC-08 y AC-11 sin prueba automática.**
Hoy: el código existe y la demo los ejercita. Plan: tres pruebas en `IntegridadTest`. Cierre: las tres en verde en CI.

**DT-04 · Sin circuit breaker ni bulkhead hacia el proveedor.**
Hoy: timeouts explícitos y reintentos con backoff en consultas. Plan: Resilience4j. Cierre: con el proveedor caído, la API responde 202 en menos de 100 ms.

## Media

**DT-02 · El outbox publica al log, no a un broker.** El contrato de eventos existe; cambiar el destino no toca el dominio. Cierre: consumidor real con deduplicación por id de evento.

**DT-05 · Sin conciliación diaria contra el reporte de liquidación.** Solo hay conciliación continua por API. Cierre: job diario que reporta diferencias.

**DT-06 · `REFUND_REQUIRED` solo marca y alerta.** No ejecuta el reembolso. Cierre: flujo de reembolso idempotente contra el proveedor.

**DT-07 · Sin autenticación propia en la API.** El perímetro es Cloudflare Access. Cierre: OAuth2 resource server con alcance por cliente.

**DT-08 · Algunos contadores viven en memoria** (duplicados descartados, timeouts). Se reinician con la aplicación. Cierre: Micrometer con Prometheus.

**DT-09 · Sin trazas distribuidas.** Hay `correlation_id` en el MDC y logs ECS, sin OpenTelemetry. Cierre: trazas enlazadas API → webhook → worker.

## Baja

**DT-03 · El simulador comparte base y proceso con la aplicación.** Cierre: simulador como servicio aparte.

**DT-11 · Sin particionamiento ni purga** de inbox, outbox y transiciones. Cierre: partición mensual y retención configurable.

**DT-12 · Sin pruebas basadas en propiedades** de secuencias de eventos. Cierre: jqwik con duplicados y desorden aleatorios.
