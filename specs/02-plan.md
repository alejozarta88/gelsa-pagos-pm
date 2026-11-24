# 02 · Plan técnico

Decisión de arquitectura: monolito modular sobre PostgreSQL (ver DAR en el documento de diseño, alternativa A2).

| Pieza | Paquete | Responsabilidad |
|---|---|---|
| API | `api` | Crea órdenes e intentos. Idempotencia por `INSERT ... ON CONFLICT`. Llama al proveedor fuera de transacción. |
| Dominio | `domain` | Máquina de estados. `apply()` es el único camino que cambia un estado. Bloquea la orden (`FOR UPDATE`) y verifica versión. |
| Proveedor | `provider` | Puerto `PaymentProvider` y cliente HTTP con timeouts. Crear no se reintenta; consultar sí. |
| Webhooks | `webhook` | `WebhookController` valida firma y guarda en el inbox. `InboxWorker` reclama con `SKIP LOCKED`, confirma con el proveedor y aplica. |
| Conciliación | `reconcile` | Resuelve `UNKNOWN` y `PENDING` vencidos con backoff exponencial. |
| Outbox | `outbox` | Publica eventos de dominio (en el PMV, al log). |
| Simulador | `sim` | Pasarela hostil para la demo. |
| Métricas | `metrics` | Contadores de integridad calculados con SQL. |

Stack: Java 21 (hilos virtuales), Spring Boot 3.4, JdbcClient (SQL explícito, sin ORM, para que las garantías se lean en el código), Flyway, PostgreSQL 16, Testcontainers, Docker Compose, Cloudflare Tunnel.
