-- Banco exclusivo do servico Partidas. Nenhum outro servico le ou escreve aqui.

CREATE TABLE IF NOT EXISTS fila_tickets (
    id            UUID PRIMARY KEY,
    jogador_id    TEXT        NOT NULL,
    aposta        INTEGER     NOT NULL CHECK (aposta >= 0),
    status        TEXT        NOT NULL, -- AGUARDANDO, PAREADO, RECUSADO, CANCELADO, CONCLUIDO
    partida_id    UUID,
    motivo        TEXT,
    criado_em     TIMESTAMPTZ NOT NULL DEFAULT now(),
    atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- R2: um jogador nao pode estar na fila ou em partida duas vezes ao mesmo tempo
CREATE UNIQUE INDEX IF NOT EXISTS ux_ticket_ativo_por_jogador
    ON fila_tickets (jogador_id) WHERE status IN ('AGUARDANDO', 'PAREADO');

CREATE INDEX IF NOT EXISTS ix_ticket_aguardando ON fila_tickets (status, criado_em);

-- R2: recurso escasso. Uma sala so pode ter uma partida.
CREATE TABLE IF NOT EXISTS salas (
    id         INTEGER PRIMARY KEY,
    partida_id UUID UNIQUE
);

CREATE TABLE IF NOT EXISTS partidas (
    id             UUID PRIMARY KEY,
    sala_id        INTEGER,
    jogador_brancas TEXT       NOT NULL,
    jogador_pretas TEXT        NOT NULL,
    aposta         INTEGER     NOT NULL,
    status         TEXT        NOT NULL, -- PENDENTE_APOSTAS, ALOCANDO_SERVIDOR, EM_ANDAMENTO, FINALIZADA, CANCELADA
    motivo         TEXT,
    vencedor       TEXT,
    servidor_id    TEXT,
    expira_em      TIMESTAMPTZ,
    criado_em      TIMESTAMPTZ NOT NULL DEFAULT now(),
    atualizado_em  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS jogadas (
    partida_id UUID    NOT NULL REFERENCES partidas (id),
    numero     INTEGER NOT NULL,
    jogador_id TEXT    NOT NULL,
    origem     TEXT    NOT NULL,
    destino    TEXT    NOT NULL,
    promocao   TEXT,
    criado_em  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (partida_id, numero)
);

-- R1: mensagens ja processadas, gravadas na mesma transacao do efeito
CREATE TABLE IF NOT EXISTS processed_messages (
    message_id    TEXT PRIMARY KEY,
    type          TEXT,
    processado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Transactional outbox: evento gravado na mesma transacao da mudanca de estado
CREATE TABLE IF NOT EXISTS outbox (
    id         BIGSERIAL PRIMARY KEY,
    message_id TEXT        NOT NULL UNIQUE,
    type       TEXT        NOT NULL,
    envelope   TEXT        NOT NULL,
    criado_em  TIMESTAMPTZ NOT NULL DEFAULT now(),
    enviado_em TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS ix_outbox_pendente ON outbox (id) WHERE enviado_em IS NULL;
