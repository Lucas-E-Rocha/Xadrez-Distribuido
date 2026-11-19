'use strict';
// Servico Ranking/Historico: Elo dos jogadores, notificacoes e historico das partidas no S3.

const express = require('express');
const { Pool } = require('pg');
const { S3Client, PutObjectCommand, GetObjectCommand } = require('@aws-sdk/client-s3');
const { SSMClient, GetParameterCommand } = require('@aws-sdk/client-ssm');
const c = require('../../node-common');

const pool = new Pool({ connectionString: process.env.DATABASE_URL, max: 10 });
const s3 = new S3Client({ ...c.awsConfig(), forcePathStyle: true });
const ssm = new SSMClient(c.awsConfig());
const BUCKET = process.env.S3_BUCKET || 'xadrez-historico';
const PARAM_K = process.env.SSM_PARAM_K || '/xadrez/ranking/k-factor';
let K = parseInt(process.env.ELO_K || '32', 10);
const RATING_INICIAL = 1200;

const SCHEMA = `
CREATE TABLE IF NOT EXISTS ratings (
  jogador_id TEXT PRIMARY KEY,
  rating     INTEGER NOT NULL DEFAULT ${RATING_INICIAL},
  partidas   INTEGER NOT NULL DEFAULT 0,
  vitorias   INTEGER NOT NULL DEFAULT 0,
  derrotas   INTEGER NOT NULL DEFAULT 0,
  empates    INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS notificacoes (
  id         BIGSERIAL PRIMARY KEY,
  jogador_id TEXT NOT NULL,
  partida_id TEXT,
  mensagem   TEXT NOT NULL,
  criado_em  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_notif_jogador ON notificacoes (jogador_id, id DESC);
${c.SQL_MENSAGERIA}`;

async function notificar(client, jogadores, partidaId, mensagem) {
  for (const j of jogadores) {
    await client.query('INSERT INTO notificacoes (jogador_id, partida_id, mensagem) VALUES ($1, $2, $3)', [j, partidaId, mensagem]);
  }
}

async function ratingDe(client, jogador) {
  await client.query('INSERT INTO ratings (jogador_id) VALUES ($1) ON CONFLICT DO NOTHING', [jogador]);
  const { rows } = await client.query('SELECT rating FROM ratings WHERE jogador_id = $1 FOR UPDATE', [jogador]);
  return rows[0].rating;
}

const handlers = {
  async PartidaIniciada(env, client) {
    const { partidaId, brancas, pretas, aposta } = env.payload;
    await notificar(client, [brancas], partidaId, `Partida iniciada contra ${pretas}. Voce joga de brancas (aposta ${aposta}).`);
    await notificar(client, [pretas], partidaId, `Partida iniciada contra ${brancas}. Voce joga de pretas (aposta ${aposta}).`);
  },

  async PartidaCancelada(env, client) {
    const { partidaId, jogadores, motivo } = env.payload;
    await notificar(client, jogadores, partidaId, `Partida cancelada: ${motivo}. Voce pode entrar na fila de novo.`);
  },

  async ApostasEstornadas(env, client) {
    const { partidaId, jogadores, valor } = env.payload;
    await notificar(client, jogadores, partidaId, `Sua aposta de ${valor} moedas foi devolvida.`);
  },

  async PremioPago(env, client) {
    const { partidaId, jogadores, valor } = env.payload;
    await notificar(client, jogadores, partidaId, `Voce recebeu ${valor} moedas.`);
  },

  // Evento com varios interessados: aqui atualiza o Elo e guarda o historico; a Carteira paga o premio.
  async PartidaFinalizada(env, client) {
    const p = env.payload;
    // S3 primeiro: e idempotente (mesma chave), entao reentrega so sobrescreve o mesmo objeto
    await s3.send(new PutObjectCommand({
      Bucket: BUCKET, Key: `partidas/${p.partidaId}.json`, ContentType: 'application/json',
      Body: JSON.stringify({ ...p, finalizadaEm: env.occurredAt }, null, 2),
    }));

    const [a, b] = p.jogadores;
    const ra = await ratingDe(client, a);
    const rb = await ratingDe(client, b);
    const ea = 1 / (1 + 10 ** ((rb - ra) / 400));
    const sa = p.empate ? 0.5 : p.vencedor === a ? 1 : 0;
    const da = Math.round(K * (sa - ea));
    const col = (s) => (s === 1 ? 'vitorias' : s === 0 ? 'derrotas' : 'empates');
    await client.query(`UPDATE ratings SET rating = rating + $2, partidas = partidas + 1, ${col(sa)} = ${col(sa)} + 1 WHERE jogador_id = $1`, [a, da]);
    await client.query(`UPDATE ratings SET rating = rating - $2, partidas = partidas + 1, ${col(1 - sa)} = ${col(1 - sa)} + 1 WHERE jogador_id = $1`, [b, da]);
    c.log('INFO', `Elo: ${a} ${ra}->${ra + da}, ${b} ${rb}->${rb - da} (K=${K})`, p.partidaId);

    const resultado = p.empate ? 'Empate' : `Vitoria de ${p.vencedor}`;
    await notificar(client, p.jogadores, p.partidaId, `${resultado} (${p.motivo}).`);
  },
};

// ---------------------------------------------------------------- HTTP
const app = express();
app.use(express.json());
app.use(c.cors);

app.get('/health', async (req, res) => {
  try {
    await pool.query('SELECT 1');
    res.json({ status: 'UP', servico: 'ranking', replica: c.REPLICA, k: K });
  } catch (e) { res.status(503).json({ status: 'DOWN', erro: e.message }); }
});

app.get('/ranking', async (req, res) => {
  try {
    const { rows } = await pool.query('SELECT * FROM ratings ORDER BY rating DESC, jogador_id LIMIT 50');
    res.json(rows);
  } catch (e) { res.status(500).json({ erro: e.message }); }
});

app.get('/notificacoes/:jogador', async (req, res) => {
  try {
    const { rows } = await pool.query(
      'SELECT partida_id, mensagem, criado_em FROM notificacoes WHERE jogador_id = $1 ORDER BY id DESC LIMIT 20', [req.params.jogador]);
    res.json(rows);
  } catch (e) { res.status(500).json({ erro: e.message }); }
});

app.get('/historico/:partidaId', async (req, res) => {
  try {
    const r = await s3.send(new GetObjectCommand({ Bucket: BUCKET, Key: `partidas/${req.params.partidaId}.json` }));
    res.type('application/json').send(await r.Body.transformToString());
  } catch (e) { res.status(404).json({ erro: 'historico nao encontrado' }); }
});

// ---------------------------------------------------------------- inicio
(async () => {
  try {
    const r = await ssm.send(new GetParameterCommand({ Name: PARAM_K }));
    K = parseInt(r.Parameter.Value, 10) || K;
    c.log('INFO', `fator K do Elo lido do SSM (${PARAM_K}): ${K}`);
  } catch (e) {
    c.log('WARN', `nao consegui ler ${PARAM_K} do SSM (${e.message}); usando K=${K}`);
  }
  await c.migrar(pool, SCHEMA);
  c.iniciarConsumidor({ pool, fila: process.env.QUEUE_NAME || 'ranking-eventos', handlers });
  const porta = parseInt(process.env.PORT || '3000', 10);
  app.listen(porta, () => c.log('INFO', `HTTP na porta ${porta}`));
})().catch((e) => { c.log('ERROR', e.stack); process.exit(1); });
