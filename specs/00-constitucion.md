# 00 · Reglas del proyecto

1. La integridad la garantiza la base de datos. Si una regla se puede expresar como restricción, índice o transacción, se expresa así; el código solo detecta el conflicto.
2. Nunca se espera al proveedor dentro de una transacción.
3. Un timeout no es un fallo: es un estado (`UNKNOWN`) que se resuelve consultando, no reintentando a ciegas.
4. Ningún cambio de estado sin su registro en `payment_state_transitions` y su evento en `outbox_events`, en la misma transacción.
5. El webhook solo valida y guarda. La lógica corre después.
6. Cada criterio de aceptación tiene una prueba que falla si se viola.
7. Lo que no se hace se escribe en `docs/DEBT.md` con su criterio de cierre.
8. Sin secretos en el repositorio. La aplicación no arranca sin `PSP_WEBHOOK_SECRET`.
