#!/usr/bin/env bash
# F6 - Broker fora do ar: para o MiniStack por 30 segundos.
# Esperado: servicos reconectam sozinhos, outbox drena, nenhuma mensagem perdida.
source "$(dirname "$0")/lib.sh"
S=$(date +%s); A="f6a$S"; B="f6b$S"

titulo "F6 - MiniStack parado por 30s"
passo "parando o MiniStack"
docker compose stop ministack
passo "$A e $B entram na fila (o Partidas continua aceitando: 202)"
TA=$(entrar_fila "$A" 20); TB=$(entrar_fila "$B" 20)
sleep 3
passo "eventos presos no outbox do Partidas"
sql_partidas "SELECT type, enviado_em IS NOT NULL AS enviado FROM outbox ORDER BY id DESC LIMIT 3"
for i in $(seq 27 -3 3); do echo "   voltando em ${i}s..."; sleep 3; done

passo "subindo o MiniStack de novo"
docker compose start ministack
for i in $(seq 1 30); do curl -fs http://localhost:4566/_ministack/health >/dev/null && break; sleep 1; done
if ! aws_cli sqs get-queue-url --queue-name partidas-eventos >/dev/null 2>&1; then
  passo "o MiniStack voltou vazio: recriando a infraestrutura (script idempotente)"
  docker compose run --rm init
fi

passo "esperando a saga terminar"
esperar_status_ticket "$TA" 60
passo "outbox drenado (pendentes = 0)"
sql_partidas "SELECT count(*) AS pendentes FROM outbox WHERE enviado_em IS NULL"
passo "logs de reconexao"
docker compose logs --no-log-prefix partidas carteira ranking 2>/dev/null | grep -iE "reconect|de volta" | tail -6
desistir_em_andamento "f6"
