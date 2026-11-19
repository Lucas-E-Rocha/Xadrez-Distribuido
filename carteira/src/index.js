'use strict';
// Servico Carteira: saldo de moedas, debito das apostas, estornos (compensacao) e premios.
// Pode rodar com varias replicas na mesma fila (R4/F8): a idempotencia e os locks no banco
// garantem que nenhuma mensagem gera efeito duplicado.

const express = require('express');
const { Pool } = require('pg');
const c = require('../../node-common');

const SALDO_INICIAL = parseInt(process.env.SALDO_INICIAL || '1000', 10);
const pool = new Pool({ connectionString: process.env.DATABASE_URL, max: 10 });

const SCHEMA = `
CREATE TABLE IF NOT EXISTS carteiras (
  jogador_id    TEXT PRIMARY KEY,
  saldo         INTEGER NOT NULL CHECK (saldo >= 0),
  atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Uma linha por (partida, jogador, tipo): segunda barreira contra efeito duplicado
CREATE TABLE IF NOT EXISTS lancamentos (
  id         BIGSERIAL PRIMARY KEY,
  jogador_id TEXT NOT NULL,
  partida_id TEXT NOT NULL,
  tipo       TEXT NOT NULL,          -- APOSTA (negativo), ESTORNO, PREMIO, DEPOSITO
  valor      INTEGER NOT NULL,
  replica    TEXT,
  criado_em  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (partida_id, jogador_id, tipo)
);
${c.SQL_MENSAGERIA}`;

async function garantirCarteiras(client, jogadores) {
  for (const j of jogadores) {
    await client.query(
      'INSERT INTO carteiras (jogador_id, saldo) VALUES ($1, $2) ON CONFLICT (jogador_id) DO NOTHING', [j, SALDO_INICIAL]);
  }
}

/** Credita uma unica vez por (partida, jogador, tipo). Retorna true se creditou agora. */
async function creditar(client, jogador, partidaId, tipo, valor) {
  const ins = await client.query(
    `INSERT INTO lancamentos (jogador_id, partida_id, tipo, valor, replica) VALUES ($1, $2, $3, $4, $5)
     ON CONFLICT (partida_id, jogador_id, tipo) DO NOTHING`, [jogador, partidaId, tipo, valor, c.REPLICA]);
  if (ins.rowCount === 0) return false;
  await client.query('UPDATE carteiras SET saldo = saldo + $2, atualizado_em = now() WHERE jogador_id = $1', [jogador, valor]);
  return true;
}

const handlers = {
  // Passo 2 da saga: debita a aposta dos DOIS jogadores ou de nenhum (transacao local).
  async ApostasSolicitadas(env, client) {
    const { partidaId, jogadores, valor } = env.payload;
    await garantirCarteiras(client, jogadores);
    // Trava as duas carteiras sempre na mesma ordem para evitar deadlock entre replicas
    const { rows } = await client.query(
      'SELECT jogador_id, saldo FROM carteiras WHERE jogador_id = ANY($1) ORDER BY jogador_id FOR UPDATE', [jogadores]);
    const semSaldo = rows.find((r) => r.saldo < valor);
    if (semSaldo) {
      c.log('WARN', `saldo insuficiente de ${semSaldo.jogador_id} (${semSaldo.saldo} < ${valor}); apostas recusadas`, partidaId);
      await c.gravarNoOutbox(client, 'ApostasRecusadas', partidaId,
        { partidaId, motivo: 'SALDO_INSUFICIENTE', jogador: semSaldo.jogador_id });
      return;
    }
    for (const j of jogadores) {
      const ins = await client.query(
        `INSERT INTO lancamentos (jogador_id, partida_id, tipo, valor, replica) VALUES ($1, $2, 'APOSTA', $3, $4)
         ON CONFLICT (partida_id, jogador_id, tipo) DO NOTHING`, [j, partidaId, -valor, c.REPLICA]);
      if (ins.rowCount > 0) {
        await client.query('UPDATE carteiras SET saldo = saldo - $2, atualizado_em = now() WHERE jogador_id = $1', [j, valor]);
      }
    }
    c.log('INFO', `apostas de ${valor} debitadas de ${jogadores.join(' e ')}`, partidaId);
    await c.gravarNoOutbox(client, 'ApostasDebitadas', partidaId, { partidaId, jogadores, valor });
  },

  // Compensacao: devolve a aposta de quem foi debitado nesta partida.
  async PartidaCancelada(env, client) {
    const { partidaId, jogadores } = env.payload;
    const { rows } = await client.query(
      "SELECT jogador_id, -valor AS valor FROM lancamentos WHERE partida_id = $1 AND tipo = 'APOSTA' AND jogador_id = ANY($2)",
      [partidaId, jogadores]);
    const estornados = [];
    for (const r of rows) {
      if (await creditar(client, r.jogador_id, partidaId, 'ESTORNO', r.valor)) estornados.push(r.jogador_id);
    }
    if (estornados.length) {
      c.log('WARN', `COMPENSACAO: aposta estornada para ${estornados.join(' e ')} (${env.payload.motivo})`, partidaId);
      await c.gravarNoOutbox(client, 'ApostasEstornadas', partidaId,
        { partidaId, jogadores: estornados, valor: rows[0].valor });
    } else {
      c.log('INFO', 'partida cancelada sem apostas debitadas: nada a estornar', partidaId);
    }
  },

  // Fim de jogo: vencedor leva o pote; empate devolve a aposta de cada um.
  async PartidaFinalizada(env, client) {
    const { partidaId, jogadores, vencedor, empate, valor } = env.payload;
    await garantirCarteiras(client, jogadores);
    const pagos = [];
    if (empate) {
      for (const j of jogadores) if (await creditar(client, j, partidaId, 'PREMIO', valor)) pagos.push(j);
    } else if (vencedor && (await creditar(client, vencedor, partidaId, 'PREMIO', valor * 2))) {
      pagos.push(vencedor);
    }
    if (pagos.length) {
      const total = empate ? valor : valor * 2;
      c.log('INFO', `premio de ${total} creditado para ${pagos.join(' e ')}`, partidaId);
      await c.gravarNoOutbox(client, 'PremioPago', partidaId, { partidaId, jogadores: pagos, valor: total });
    }
  },
};

// ---------------------------------------------------------------- HTTP
const app = express();
app.use(express.json());
app.use(c.cors);

app.get('/health', async (req, res) => {
  try {
    const { rows } = await pool.query('SELECT count(*)::int AS n FROM outbox WHERE enviado_em IS NULL');
    res.json({ status: 'UP', servico: 'carteira', replica: c.REPLICA, outboxPendente: rows[0].n });
  } catch (e) {
    res.status(503).json({ status: 'DOWN', erro: e.message });
  }
});

app.get('/carteiras/:jogador', async (req, res) => {
  try {
    await pool.query('INSERT INTO carteiras (jogador_id, saldo) VALUES ($1, $2) ON CONFLICT DO NOTHING',
      [req.params.jogador, SALDO_INICIAL]);
    const { rows } = await pool.query('SELECT jogador_id, saldo FROM carteiras WHERE jogador_id = $1', [req.params.jogador]);
    res.json(rows[0]);
  } catch (e) { res.status(500).json({ erro: e.message }); }
});

app.get('/carteiras/:jogador/lancamentos', async (req, res) => {
  try {
    const { rows } = await pool.query(
      'SELECT partida_id, tipo, valor, replica, criado_em FROM lancamentos WHERE jogador_id = $1 ORDER BY id DESC LIMIT 50',
      [req.params.jogador]);
    res.json(rows);
  } catch (e) { res.status(500).json({ erro: e.message }); }
});

// Deposito manual para testes (ex.: dar saldo a um jogador antes da demo)
app.post('/carteiras/:jogador/deposito', async (req, res) => {
  const valor = parseInt(req.body && req.body.valor, 10);
  if (!(valor > 0)) return res.status(400).json({ erro: 'valor deve ser positivo' });
  try {
    await c.comTransacao(pool, async (cl) => {
      await garantirCarteiras(cl, [req.params.jogador]);
      await cl.query(
        "INSERT INTO lancamentos (jogador_id, partida_id, tipo, valor, replica) VALUES ($1, $2, 'DEPOSITO', $3, $4)",
        [req.params.jogador, 'deposito-' + Date.now() + '-' + Math.random().toString(36).slice(2), valor, c.REPLICA]);
      await cl.query('UPDATE carteiras SET saldo = saldo + $2 WHERE jogador_id = $1', [req.params.jogador, valor]);
    });
    const { rows } = await pool.query('SELECT jogador_id, saldo FROM carteiras WHERE jogador_id = $1', [req.params.jogador]);
    res.json(rows[0]);
  } catch (e) { res.status(500).json({ erro: e.message }); }
});

// ---------------------------------------------------------------- inicio
(async () => {
  await c.migrar(pool, SCHEMA);
  c.iniciarRelayOutbox(pool);
  c.iniciarConsumidor({
    pool,
    fila: process.env.QUEUE_NAME || 'carteira-eventos',
    handlers,
    atrasoMs: parseInt(process.env.ATRASO_PROCESSAMENTO_MS || '0', 10),
  });
  const porta = parseInt(process.env.PORT || '3000', 10);
  app.listen(porta, () => c.log('INFO', `HTTP na porta ${porta}`));
})().catch((e) => { c.log('ERROR', e.stack); process.exit(1); });
