# Pagos asíncronos · PMV

Prueba técnica Desarrollador Java Senior. Integración con una pasarela de pagos que responde tarde,
duplica notificaciones, las desordena o las pierde.

El documento de diseño pide garantías; este PMV las demuestra. Trae una pasarela simulada hostil y un panel
que lanza cientos de compras concurrentes contra ella y mide lo que importa: cobros duplicados, órdenes
inconsistentes y divergencias con el proveedor. Las tres deben quedar en cero.

## Correr local

```bash
cp .env.example .env          # poner DB_PASSWORD y PSP_WEBHOOK_SECRET
docker compose up -d --build
# Panel: http://127.0.0.1:8085
```

Pruebas (requiere Docker, usan PostgreSQL real con Testcontainers):

```bash
mvn verify
```

## Qué mirar primero

| Si le interesa… | Vaya a |
|---|---|
| Qué se pidió y cómo se acepta | `specs/01-especificacion.md` (AC-01 a AC-12) |
| Las garantías en la base de datos | `src/main/resources/db/migration/V1__esquema.sql` |
| La máquina de estados | `domain/PaymentStatus.java`, `domain/PaymentService.java` |
| Idempotencia en la API | `api/CheckoutService.java` |
| Webhooks | `webhook/WebhookController.java` (recibe), `webhook/InboxWorker.java` (procesa) |
| Conciliación | `reconcile/Reconciler.java` |
| Pruebas de concurrencia | `src/test/.../IntegridadTest.java` |
| Decisiones y lo que falta | `docs/decisiones.md`, `docs/DEBT.md` |

## API

| Método | Ruta | Nota |
|---|---|---|
| POST | `/api/orders` | `{customerRef, amount, currency}` |
| POST | `/api/orders/{id}/payments` | Cabecera `Idempotency-Key` obligatoria. 201 creado, 200 repetido, 202 en verificación, 409 ya hay un pago activo, 422 clave reutilizada con otra petición |
| GET | `/api/orders/{id}` | Orden, intentos y línea de tiempo completa |
| POST | `/webhooks/psp` | Firma HMAC-SHA256 en `X-Psp-Signature` sobre `X-Psp-Timestamp.cuerpo` |
| GET | `/api/metricas` | Contadores de integridad |
| POST | `/api/demo/ejecutar` | Corrida de caos |

## Cómo se construyó

Desarrollo guiado por especificación: `specs/00` (reglas) → `specs/01` (criterios de aceptación) →
`specs/02` (plan) → `specs/03` (tareas con su criterio y su prueba). Cada prueba lleva en el nombre el AC que protege.
