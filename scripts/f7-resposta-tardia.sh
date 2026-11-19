#!/usr/bin/env bash
# F7 - Resposta tardia: latencia da dependencia (15s) maior que o prazo (10s).
# Esperado: partida cancelada por timeout com estorno; quando o servidor chega atrasado,
# o Partidas pede a liberacao dele (compensa o resultado tardio).
source "$(dirname "$0")/lib.sh"
S=$(date +%s); A="f7a$S"; B="f7b$S"

titulo "F7 - alocador com 15s de latencia, prazo de 10s"
LATENCIA_MS=15000 JITTER_MS=0 TAXA_FALHA=0 docker compose up -d --no-deps alocador-sim
sleep 3
passo "saldo antes"; saldo "$A"; saldo "$B"
TA=$(entrar_fila "$A" 40); TB=$(entrar_fila "$B" 40)
esperar_status_ticket "$TA" 30
passo "aguardando a resposta atrasada do alocador..."
sleep 12
passo "saldo depois (estornado)"; saldo "$A"; saldo "$B"
sql_carteira "SELECT jogador_id, tipo, valor FROM lancamentos WHERE jogador_id IN ('$A','$B') ORDER BY id"
passo "logs da compensacao do resultado tardio"
docker compose logs --no-log-prefix partidas alocador-sim 2>/dev/null | grep -E "atrasado|RESULTADO_TARDIO|TIMEOUT_ALOCACAO" | tail -6
docker compose up -d --no-deps alocador-sim
