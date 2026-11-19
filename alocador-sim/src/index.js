'use strict';
// Dependencia lenta e instavel SIMULADA (R5): "provedor de servidores de jogo".
// Recebe AlocacaoSolicitada, demora LATENCIA_MS (+/- JITTER_MS) e responde
// ServidorAlocado ou, com probabilidade TAXA_FALHA, AlocacaoFalhou.
//
// Como um parceiro externo de verdade, ele aceita o pedido na hora (apaga a mensagem)
// e responde depois. Por isso a resposta pode chegar depois do prazo da partida (F7).

const http = require('http');
const crypto = require('crypto');
const { GetQueueUrlCommand, ReceiveMessageCommand, DeleteMessageCommand } = require('@aws-sdk/client-sqs');
const c = require('../../node-common');

const num = (v, d) => (v === undefined || v === '' ? d : Number(v));
const LATENCIA_MS = num(process.env.LATENCIA_MS, 1500);
const JITTER_MS = num(process.env.JITTER_MS, 500);
const TAXA_FALHA = num(process.env.TAXA_FALHA, 0.2);
const FILA = process.env.QUEUE_NAME || 'alocador-solicitacoes';

const respondidos = new Map(); // partidaId -> resposta (um pedido repetido recebe a mesma resposta)
const servidoresAtivos = new Set();

async function publicarComRetry(env) {
  for (let i = 0; ; i++) {
    try { await c.publicar(env); return; } catch (e) {
      if (i === 0) c.log('WARN', `broker indisponivel ao responder (${e.message}); tentando de novo`, env.correlationId);
      await c.dormir(Math.min(1000 * 2 ** i, 10000));
    }
  }
}

async function alocar(p, corr) {
  if (respondidos.has(p.partidaId)) {
    await publicarComRetry(c.criarEnvelope(respondidos.get(p.partidaId).type, corr, respondidos.get(p.partidaId).payload));
    return;
  }
  const atraso = Math.max(0, LATENCIA_MS + (Math.random() * 2 - 1) * JITTER_MS);
  c.log('INFO', `alocando servidor para a sala ${p.salaId} (vai levar ~${Math.round(atraso)}ms)`, corr);
  await c.dormir(atraso);
  let resp;
  if (Math.random() < TAXA_FALHA) {
    resp = { type: 'AlocacaoFalhou', payload: { partidaId: p.partidaId, motivo: 'PROVEDOR_INDISPONIVEL' } };
    c.log('WARN', 'falha simulada ao alocar servidor', corr);
  } else {
    const servidorId = 'srv-' + crypto.randomBytes(3).toString('hex');
    servidoresAtivos.add(servidorId);
    resp = { type: 'ServidorAlocado', payload: { partidaId: p.partidaId, servidorId } };
    c.log('INFO', `servidor ${servidorId} pronto`, corr);
  }
  respondidos.set(p.partidaId, resp);
  await publicarComRetry(c.criarEnvelope(resp.type, corr, resp.payload));
}

async function loop() {
  let url = null;
  let avisado = false;
  for (;;) {
    try {
      if (!url) url = (await c.sqs.send(new GetQueueUrlCommand({ QueueName: FILA }))).QueueUrl;
      const r = await c.sqs.send(new ReceiveMessageCommand({ QueueUrl: url, MaxNumberOfMessages: 10, WaitTimeSeconds: 10 }));
      if (avisado) { c.log('INFO', 'reconectado ao broker'); avisado = false; }
      for (const m of r.Messages || []) {
        let env;
        try {
          let n = JSON.parse(m.Body);
          if (n.Type === 'Notification') n = JSON.parse(n.Message);
          env = c.validar(n);
        } catch (e) {
          c.log('ERROR', `mensagem invalida: ${e.message}`);
          continue; // fica na fila; vai para a DLQ apos o limite de tentativas
        }
        await c.sqs.send(new DeleteMessageCommand({ QueueUrl: url, ReceiptHandle: m.ReceiptHandle }));
        if (env.type === 'AlocacaoSolicitada') {
          alocar(env.payload, env.correlationId).catch((e) => c.log('ERROR', e.message, env.correlationId));
        } else if (env.type === 'LiberacaoServidorSolicitada') {
          const s = env.payload.servidorId;
          if (s && servidoresAtivos.delete(s)) c.log('INFO', `servidor ${s} liberado (${env.payload.motivo})`, env.correlationId);
          else c.log('INFO', `liberacao de ${s || 'servidor nenhum'} ignorada (ja liberado)`, env.correlationId);
        }
      }
    } catch (e) {
      if (!avisado) { c.log('WARN', `broker indisponivel (${e.message}); tentando reconectar...`); avisado = true; }
      await c.dormir(2000);
    }
  }
}

c.log('INFO', `simulador: LATENCIA_MS=${LATENCIA_MS} JITTER_MS=${JITTER_MS} TAXA_FALHA=${TAXA_FALHA}`);
loop();
http.createServer((req, res) => {
  res.setHeader('Content-Type', 'application/json');
  res.end(JSON.stringify({ status: 'UP', servico: 'alocador-sim', LATENCIA_MS, JITTER_MS, TAXA_FALHA,
    servidoresAtivos: [...servidoresAtivos] }));
}).listen(parseInt(process.env.PORT || '3000', 10));
