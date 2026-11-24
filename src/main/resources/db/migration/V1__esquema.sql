-- Las garantías de integridad viven aquí. Ver specs/01-especificacion.md (AC-01 a AC-09).

CREATE TABLE orders (
  id            uuid PRIMARY KEY,
  customer_ref  varchar(64)   NOT NULL,
  amount        numeric(19,4) NOT NULL CHECK (amount > 0),
  currency      char(3)       NOT NULL,
  status        varchar(20)   NOT NULL CHECK (status IN ('PENDING_PAYMENT','PAID','PAYMENT_FAILED','EXPIRED','CANCELLED')),
  version       int           NOT NULL DEFAULT 0,
  created_at    timestamptz   NOT NULL DEFAULT now(),
  updated_at    timestamptz   NOT NULL DEFAULT now()
);

CREATE TABLE payment_attempts (
  id                  uuid PRIMARY KEY,           -- se envía al proveedor como referencia
  order_id            uuid          NOT NULL REFERENCES orders(id),
  idempotency_key     varchar(80)   NOT NULL,
  request_hash        char(64)      NOT NULL,
  provider            varchar(30)   NOT NULL,
  provider_payment_id varchar(80),
  status              varchar(20)   NOT NULL CHECK (status IN ('CREATED','PENDING','UNKNOWN','APPROVED','DECLINED','EXPIRED','REFUND_REQUIRED','REFUNDED')),
  amount              numeric(19,4) NOT NULL CHECK (amount > 0),
  currency            char(3)       NOT NULL,
  redirect_url        text,
  version             int           NOT NULL DEFAULT 0,
  reconcile_attempts  int           NOT NULL DEFAULT 0,
  next_check_at       timestamptz,
  created_at          timestamptz   NOT NULL DEFAULT now(),
  updated_at          timestamptz   NOT NULL DEFAULT now(),
  CONSTRAINT uq_idempotency_key  UNIQUE (idempotency_key),
  CONSTRAINT uq_provider_payment UNIQUE (provider, provider_payment_id)
);
-- Un solo intento activo por orden: no se puede cobrar en paralelo (AC-02).
CREATE UNIQUE INDEX uq_one_active_attempt ON payment_attempts (order_id)
  WHERE status IN ('CREATED','PENDING','UNKNOWN');
-- Un solo aprobado por orden: el segundo se desvía a REFUND_REQUIRED (AC-08).
CREATE UNIQUE INDEX uq_one_approved ON payment_attempts (order_id) WHERE status = 'APPROVED';
CREATE INDEX ix_attempts_reconcile ON payment_attempts (next_check_at) WHERE status IN ('PENDING','UNKNOWN');
CREATE INDEX ix_attempts_order ON payment_attempts (order_id);

CREATE TABLE payment_inbox (
  id                  bigserial PRIMARY KEY,
  provider            varchar(30)  NOT NULL,
  provider_event_id   varchar(80)  NOT NULL,
  provider_payment_id varchar(80),
  reference           varchar(80)  NOT NULL,
  event_status        varchar(20)  NOT NULL,
  payload             jsonb        NOT NULL,
  status              varchar(12)  NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED','PROCESSED','IGNORED','DEAD')),
  attempts            int          NOT NULL DEFAULT 0,
  next_retry_at       timestamptz  NOT NULL DEFAULT now(),
  last_error          text,
  received_at         timestamptz  NOT NULL DEFAULT now(),
  processed_at        timestamptz,
  CONSTRAINT uq_inbox_event UNIQUE (provider, provider_event_id)   -- AC-04
);
CREATE INDEX ix_inbox_pending ON payment_inbox (next_retry_at) WHERE status = 'RECEIVED';

CREATE TABLE payment_state_transitions (
  id          bigserial PRIMARY KEY,
  attempt_id  uuid        NOT NULL REFERENCES payment_attempts(id),
  from_status varchar(20),
  to_status   varchar(20) NOT NULL,
  source      varchar(12) NOT NULL CHECK (source IN ('API','WEBHOOK','RECONCILER','MANUAL')),
  detail      text,
  at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_transitions_attempt ON payment_state_transitions (attempt_id, at);

CREATE TABLE outbox_events (
  id           uuid PRIMARY KEY,
  aggregate_id uuid         NOT NULL,
  event_type   varchar(40)  NOT NULL,
  payload      jsonb        NOT NULL,
  status       varchar(10)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','DEAD')),
  attempts     int          NOT NULL DEFAULT 0,
  created_at   timestamptz  NOT NULL DEFAULT now(),
  sent_at      timestamptz
);
CREATE INDEX ix_outbox_pending ON outbox_events (created_at) WHERE status = 'PENDING';

-- Estado interno del proveedor simulado. No es parte del dominio (DT-03).
CREATE TABLE psp_sim_payments (
  id              varchar(80) PRIMARY KEY,
  reference       varchar(80) NOT NULL,
  idempotency_key varchar(80) NOT NULL UNIQUE,
  amount          numeric(19,4) NOT NULL,
  status          varchar(20) NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_psp_sim_reference ON psp_sim_payments (reference);
