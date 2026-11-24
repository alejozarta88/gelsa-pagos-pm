# Decisiones

| ID | Decisión | Por qué | Alternativa descartada |
|---|---|---|---|
| D-01 | Monolito modular sobre PostgreSQL | Logra las mismas garantías que las opciones con más infraestructura, con dos componentes que operar. Ver DAR. | Kafka + CDC, Temporal, event sourcing |
| D-02 | Idempotencia con restricciones únicas, no con chequeos en código | Un "si existe, no lo crees" tiene ventana de carrera entre lectura y escritura. La restricción no. | Redis: queda fuera de la transacción de negocio |
| D-03 | `UNKNOWN` como estado de primera clase | Tras un timeout el cobro pudo ocurrir. Reintentar a ciegas es la causa típica del doble cobro. | Reintentar la creación |
| D-04 | Bloqueo corto de la fila de la orden en cada transición, más versión optimista en el intento | Serializa lo que toca una orden sin bloquear las demás; la versión detecta escrituras fuera de `apply`. | Solo versión optimista: obliga a reintentos en ráfagas de webhooks |
| D-05 | El webhook avisa, la API del proveedor confirma | El orden de llegada no es confiable; el estado del proveedor sí. Resuelve los eventos desordenados sin lógica extra. | Aplicar el contenido del webhook tal cual |
| D-06 | Reclamar trabajo con concesión de 30 s (`UPDATE ... RETURNING` sobre `SKIP LOCKED`) | Permite llamar al proveedor sin transacción abierta y sobrevive a la caída de un worker. | Mantener el `FOR UPDATE` durante la llamada |
| D-07 | JdbcClient con SQL explícito | Las garantías están en el SQL; un ORM las esconde. | JPA/Hibernate |
| D-08 | Pasarela simulada dentro de la aplicación | Demo autocontenida y reproducible en un VPS pequeño. | Servicio aparte (DT-03) |
| D-09 | Exposición por Cloudflare Tunnel con Access | Cero puertos abiertos en el VPS; acceso por correo autorizado. | Puerto público con proxy inverso |
