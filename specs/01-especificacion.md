# 01 · Especificación y criterios de aceptación

Fuente: enunciado "Integración asíncrona con una pasarela de pagos" (§4 requisitos funcionales, §5 no funcionales).

| AC | Criterio | Prueba | Estado |
|---|---|---|---|
| AC-01 | Dos peticiones con la misma `Idempotency-Key` devuelven el mismo intento y el proveedor recibe un solo cobro. | `ac01_laMismaClaveDevuelveElMismoIntento` | Cumple |
| AC-02 | Una orden no puede tener dos intentos activos. Un segundo intento concurrente recibe 409. | `ac02_dosIntentosConClavesDistintasEnParaleloSoloCreaUno` | Cumple |
| AC-03 | Una clave reutilizada con otra petición recibe 422. | — | Cumple sin prueba automática (DT-10) |
| AC-04 | Un webhook repetido, aun en paralelo, produce un solo efecto: una transición, un evento de outbox, una orden pagada. | `ac04_webhookDuplicadoEnParaleloTieneUnSoloEfecto` | Cumple |
| AC-05 | Un evento tardío no retrocede el estado; queda `IGNORED` con su motivo. | `ac05_unEventoTardioNoRetrocedeElEstado` | Cumple |
| AC-06 | Si el proveedor no responde a tiempo, el intento queda `UNKNOWN`, la API responde 202, y la conciliación lo resuelve con el estado real del proveedor. | `ac06_timeoutQuedaDesconocidoYLaConciliacionLoResuelve` | Cumple |
| AC-07 | Un webhook con firma inválida o vencida se rechaza con 401 y no deja rastro en el inbox. | `ac07_firmaInvalidaNoTieneEfecto` | Cumple |
| AC-08 | Un aprobado que no puede quedarse (orden ya pagada o cerrada, intento ya cerrado) pasa a `REFUND_REQUIRED` y genera alerta. | Índice `uq_one_approved` y `PaymentService.apply` | Cumple sin prueba automática (DT-10) |
| AC-09 | Si el proveedor no conoce la referencia de un `UNKNOWN`, el intento pasa a `EXPIRED`: no hubo cobro. | `Reconciler` | Cumple; verificado en la demo |
| AC-10 | Toda la vida de un pago se reconstruye con una consulta (`GET /api/orders/{id}`). | `payment_state_transitions` | Cumple |
| AC-11 | Un evento que falla se reintenta con backoff y, al agotar 8 intentos, queda `DEAD` con alerta en el log. | `InboxWorker` | Cumple; sin prueba automática (DT-10) |
| AC-12 | Bajo caos (duplicados, desorden, timeouts, pérdidas, clientes impacientes) los cobros duplicados, las órdenes inconsistentes y las divergencias con el proveedor son cero. | Panel y `GET /api/metricas` | Verificado por corrida manual |

## Reglas de negocio supuestas

- B1: varios intentos por orden, uno activo a la vez.
- B2: un `PENDING` sin noticias se reconsulta a los 20 s en la demo (30 min propuesto en producción).
- B3: un aprobado sobre una orden cerrada se reembolsa (en el PMV solo se marca y alerta).
- B4: sin pagos parciales.
