#!/usr/bin/env bash
# F2 - Servico morre no meio: docker kill na replica da Carteira enquanto ela processa o debito.
# Esperado: a mensagem volta para a fila (visibility timeout) e outra replica aplica o efeito UMA vez.
source "$(dirname "$0")/lib.sh"
S=$(date +%s); A="f2a$S"; B="f2b$S"

titulo "F2 - matar o consumidor no meio do processamento"
passo "Carteira com 2 replicas e 8s de processamento (para dar tempo de matar)"
ATRASO_PROCESSAMENTO_MS=8000 docker compose up -d --no-deps carteira
sleep 6

passo "$A e $B entram na fila apostando 30"
TA=$(entrar_fila "$A" 30); TB=$(entrar_fila "$B" 30)

passo "procurando a replica que pegou a mensagem..."
REPLICA=""
for i in $(seq 1 40); do
  PID=$(ticket "$TA" | sed -nE 's/.*"partidaId":"([0-9a-f-]{36})".*/\1/p')
  if [[ -n "$PID" ]]; then
    REPLICA=$(docker compose logs --no-log-prefix carteira 2>/dev/null | grep "recebido ApostasSolicitadas" | grep -F "$PID" \
      | tail -1 | sed -nE 's/.*\[carteira@([0-9a-f]+)\].*/\1/p')
    [[ -n "$REPLICA" ]] && break
  fi
  sleep 0.5
done
[[ -z "$REPLICA" ]] && { echo "nao achei a replica nos logs"; exit 1; }
passo "docker kill na replica $REPLICA"
docker kill "$REPLICA"

passo "esperando a reentrega (visibility timeout ~15s) e o fim da saga"
esperar_status_ticket "$TA" 60

passo "lancamentos: exatamente UMA aposta por jogador, feita pela outra replica"
sql_carteira "SELECT jogador_id, tipo, valor, replica FROM lancamentos WHERE jogador_id IN ('$A','$B') ORDER BY id"
passo "a replica morta volta sozinha (restart: unless-stopped)"
docker compose ps carteira

passo "voltando a Carteira ao normal"
docker compose up -d --no-deps carteira
desistir_em_andamento "f2"
