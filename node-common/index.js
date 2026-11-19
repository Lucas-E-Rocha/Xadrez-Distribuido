'use strict';
// Biblioteca compartilhada pelos servicos Node (Carteira, Ranking e o alocador simulado).
// Mensageria (SNS + SQS), outbox, idempotencia, reconexao e validacao de contratos.

const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const { SNSClient, ListTopicsCommand, PublishCommand } = require('@aws-sdk/client-sns');
const { SQSClient, GetQueueUrlCommand, ReceiveMessageCommand, DeleteMessageCommand } = require('@aws-sdk/client-sqs');
const Ajv2020 = require('ajv/dist/2020');
const addFormats = require('ajv-formats');

const REPLICA = os.hostname();
const SERVICO = process.env.SERVICE_NAME || 'servico';

// ---------------------------------------------------------------- log
function log(nivel, msg, corr) {
  const ts = new Date().toISOString().substring(11, 23);
  const c = corr ? ` [corr=${corr}]` : '';
  console.log(`${ts} ${nivel.padEnd(5)} [${SERVICO}@${REPLICA}]${c} ${msg}`);
}

// ---------------------------------------------------------------- AWS
function awsConfig() {
  const cfg = {
    region: process.env.AWS_REGION || 'us-east-1',
    credentials: {
      accessKeyId: process.env.AWS_ACCESS_KEY_ID || 'test',
      secretAccessKey: process.env.AWS_SECRET_ACCESS_KEY || 'test',
    },
  };
  if (process.env.AWS_ENDPOINT_URL) cfg.endpoint = process.env.AWS_ENDPOINT_URL;
  return cfg;
}
const sns = new SNSClient(awsConfig());
// useQueueUrlAsEndpoint=false: sempre usa AWS_ENDPOINT_URL, mesmo que a QueueUrl devolvida aponte para localhost
const sqs = new SQSClient({ ...awsConfig(), useQueueUrlAsEndpoint: false });

// ---------------------------------------------------------------- contratos
const CONTRACTS_DIR = process.env.CONTRACTS_DIR || path.join(__dirname, '..', 'contracts');
const ajv = new Ajv2020({ strict: false, allErrors: true });
addFormats(ajv);
const validarEnvelope = ajv.compile(lerJson(path.join(CONTRACTS_DIR, 'envelope.schema.json')));
const validadores = {};
for (const f of fs.readdirSync(path.join(CONTRACTS_DIR, 'events'))) {
  const m = f.match(/^(\w+)\.v(\d+)\.schema\.json$/);
  if (m) validadores[`${m[1]}@${m[2]}`] = ajv.compile(lerJson(path.join(CONTRACTS_DIR, 'events', f)));
}
function lerJson(p) {
  const s = JSON.parse(fs.readFileSync(p, 'utf8'));
  delete s.$id;
  return s;
}

class MensagemInvalida extends Error {}

function validar(env) {
  if (!validarEnvelope(env)) {
    throw new MensagemInvalida('envelope invalido: ' + ajv.errorsText(validarEnvelope.errors));
  }
  const v = validadores[`${env.type}@${env.version}`];
  if (v && !v(env.payload)) {
    throw new MensagemInvalida(`payload de ${env.type} v${env.version} invalido: ` + ajv.errorsText(v.errors));
  }
  return env;
}

function criarEnvelope(type, correlationId, payload, version = 1) {
  return {
    messageId: crypto.randomUUID(),
    correlationId: String(correlationId),
    type,
    version,
    occurredAt: new Date().toISOString(),
    payload,
  };
}

// ---------------------------------------------------------------- publicacao
const PREFIXO = process.env.TOPIC_PREFIX || 'xadrez-';
const topicos = new Map();

async function arnDoTopico(type) {
  if (topicos.has(type)) return topicos.get(type);
  let token;
  do {
    const r = await sns.send(new ListTopicsCommand({ NextToken: token }));
    for (const t of r.Topics || []) {
      const nome = t.TopicArn.substring(t.TopicArn.lastIndexOf(':') + 1);
      if (nome.startsWith(PREFIXO)) topicos.set(nome.substring(PREFIXO.length), t.TopicArn);
    }
    token = r.NextToken;
  } while (token);
  if (!topicos.has(type)) throw new Error(`topico ${PREFIXO}${type} nao existe (rodou o infra/init.sh?)`);
  return topicos.get(type);
}

async function publicar(env) {
  const TopicArn = await arnDoTopico(env.type);
  await sns.send(new PublishCommand({
    TopicArn,
    Message: JSON.stringify(env),
    MessageAttributes: { type: { DataType: 'String', StringValue: env.type } },
  }));
}

// ---------------------------------------------------------------- banco
async function comTransacao(pool, fn) {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const r = await fn(client);
    await client.query('COMMIT');
    return r;
  } catch (e) {
    await client.query('ROLLBACK').catch(() => {});
    throw e;
  } finally {
    client.release();
  }
}

async function migrar(pool, sql) {
  for (let tentativa = 1; ; tentativa++) {
    try {
      await pool.query(sql);
      log('INFO', 'schema do banco pronto');
      return;
    } catch (e) {
      if (tentativa >= 30) throw e;
      log('WARN', `banco indisponivel (${e.message}); tentando de novo...`);
      await dormir(2000);
    }
  }
}

const SQL_MENSAGERIA = `
CREATE TABLE IF NOT EXISTS processed_messages (
  message_id    TEXT PRIMARY KEY,
  type          TEXT,
  processado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS outbox (
  id         BIGSERIAL PRIMARY KEY,
  message_id TEXT NOT NULL UNIQUE,
  type       TEXT NOT NULL,
  envelope   TEXT NOT NULL,
  criado_em  TIMESTAMPTZ NOT NULL DEFAULT now(),
  enviado_em TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS ix_outbox_pendente ON outbox (id) WHERE enviado_em IS NULL;
`;

// ---------------------------------------------------------------- outbox
/** Grava o evento no outbox usando o client da transacao atual (mesma transacao do efeito). */
async function gravarNoOutbox(client, type, correlationId, payload) {
  const env = criarEnvelope(type, correlationId, payload);
  await client.query('INSERT INTO outbox (message_id, type, envelope) VALUES ($1, $2, $3)',
    [env.messageId, type, JSON.stringify(env)]);
  return env;
}

function iniciarRelayOutbox(pool, intervaloMs = 500) {
  let brokerFora = false;
  const ciclo = async () => {
    try {
      await comTransacao(pool, async (c) => {
        const { rows } = await c.query(
          'SELECT id, type, envelope FROM outbox WHERE enviado_em IS NULL ORDER BY id LIMIT 50 FOR UPDATE SKIP LOCKED');
        for (const r of rows) {
          const env = JSON.parse(r.envelope);
          await publicar(env);
          await c.query('UPDATE outbox SET enviado_em = now() WHERE id = $1', [r.id]);
          log('INFO', `evento publicado: ${r.type}`, env.correlationId);
        }
      });
      if (brokerFora) { brokerFora = false; log('INFO', 'broker de volta: outbox drenando'); }
    } catch (e) {
      if (!brokerFora) log('WARN', `nao consegui publicar o outbox (${e.message}); eventos guardados ate o broker voltar`);
      brokerFora = true;
    } finally {
      setTimeout(ciclo, intervaloMs);
    }
  };
  setTimeout(ciclo, intervaloMs);
}

// ---------------------------------------------------------------- consumo
function desembrulhar(body) {
  let n;
  try { n = JSON.parse(body); } catch { throw new MensagemInvalida('corpo nao e JSON valido'); }
  if (n && n.Type === 'Notification' && typeof n.Message === 'string') {
    try { n = JSON.parse(n.Message); } catch { throw new MensagemInvalida('Message do SNS nao e JSON valido'); }
  }
  return n;
}

/**
 * Loop de consumo com:
 *  - R1: messageId gravado em processed_messages na MESMA transacao do handler;
 *  - R6: erro de broker -> espera com backoff e tenta de novo, sem reiniciar o processo;
 *  - F4: mensagem invalida nao e apagada; o SQS reentrega e, no limite, manda para a DLQ.
 * handlers: { [type]: async (env, client) => {} }. Tipos sem handler sao reconhecidos e ignorados.
 */
function iniciarConsumidor({ pool, fila, handlers, atrasoMs = 0 }) {
  let url = null;
  let espera = 1000;
  let avisado = false;

  const tratar = async (m) => {
    const tentativa = (m.Attributes && m.Attributes.ApproximateReceiveCount) || '?';
    let env;
    try {
      env = validar(desembrulhar(m.Body));
      const novo = await comTransacao(pool, async (c) => {
        const ins = await c.query(
          'INSERT INTO processed_messages (message_id, type) VALUES ($1, $2) ON CONFLICT (message_id) DO NOTHING',
          [env.messageId, env.type]);
        if (ins.rowCount === 0) return false;
        const h = handlers[env.type];
        log('INFO', `recebido ${env.type} v${env.version} (tentativa ${tentativa})`, env.correlationId);
        if (atrasoMs > 0) await dormir(atrasoMs); // F2: janela para dar docker kill no meio do processamento
        if (h) await h(env, c);
        return true;
      });
      if (!novo) log('INFO', `mensagem duplicada ignorada: ${env.type} ${env.messageId}`, env.correlationId);
      await sqs.send(new DeleteMessageCommand({ QueueUrl: url, ReceiptHandle: m.ReceiptHandle }));
    } catch (e) {
      if (e instanceof MensagemInvalida) {
        log('ERROR', `mensagem invalida (tentativa ${tentativa}): ${e.message}. Vai para a DLQ apos o limite.`);
      } else {
        log('ERROR', `falha ao processar (tentativa ${tentativa}): ${e.message}. Sera reentregue.`, env && env.correlationId);
      }
    }
  };

  const loop = async () => {
    for (;;) {
      try {
        if (!url) {
          url = (await sqs.send(new GetQueueUrlCommand({ QueueName: fila }))).QueueUrl;
          log('INFO', `consumindo a fila ${fila}`);
        }
        const r = await sqs.send(new ReceiveMessageCommand({
          QueueUrl: url, MaxNumberOfMessages: 10, WaitTimeSeconds: 10,
          MessageSystemAttributeNames: ['ApproximateReceiveCount'],
          AttributeNames: ['ApproximateReceiveCount'],
        }));
        if (avisado) { log('INFO', `reconectado ao broker; voltando a consumir ${fila}`); avisado = false; }
        espera = 1000;
        for (const m of r.Messages || []) await tratar(m);
      } catch (e) {
        if (!avisado) { log('WARN', `broker indisponivel (${e.message}); tentando reconectar...`); avisado = true; }
        await dormir(espera);
        espera = Math.min(espera * 2, 10000);
      }
    }
  };
  loop();
}

function dormir(ms) { return new Promise((r) => setTimeout(r, ms)); }

// ---------------------------------------------------------------- http
function cors(req, res, next) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
  if (req.method === 'OPTIONS') return res.sendStatus(204);
  next();
}

module.exports = {
  log, REPLICA, sns, sqs, awsConfig,
  criarEnvelope, validar, MensagemInvalida, publicar,
  comTransacao, migrar, SQL_MENSAGERIA,
  gravarNoOutbox, iniciarRelayOutbox, iniciarConsumidor, dormir, cors,
};
