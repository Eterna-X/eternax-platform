-- Local copy of closure state, fed by the closure event topic, so hot paths never call the closure service.
CREATE TABLE closed_period_lock (
    period_id VARCHAR(64)  PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL,
    rail      VARCHAR(30)  NOT NULL,
    from_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    to_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    closed    BOOLEAN      NOT NULL,
    as_of     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_closed_lookup ON closed_period_lock (tenant_id, rail, from_at, to_at) WHERE closed;
