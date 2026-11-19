#!/usr/bin/env bash
# F1 - Compensacao: taxa de falha da dependencia em 100%.
# Esperado: apostas debitadas e depois estornadas, sala liberada, jogadores notificados.
source "$(dirname "$0")/lib.sh"
S=$(date +%s); A="f1a$S"; B="f1b$S"

titulo "F1 - alocador de servidores falhando 100%"
passo "subindo o alocador com TAXA_FALHA=1"
TAXA_FALHA=1 docker compose up -d --no-deps alocador-sim
sleep 3

passo "saldo inicial"; saldo "$A"; saldo "$B"
passo "$A e $B entram na fila apostando 100"
TA=$(entrar_fila "$A" 100); TB=$(entrar_fila "$B" 100)
passo "acompanhando o ticket de $A"
esperar_status_ticket "$TA" 30
sleep 3
passo "saldo final (deve ser igual ao inicial)"; saldo "$A"; saldo "$B"
passo "lancamentos na Carteira (APOSTA -100 seguido de ESTORNO +100)"
sql_carteira "SELECT jogador_id, tipo, valor, replica FROM lancamentos WHERE jogador_id IN ('$A','$B') ORDER BY id"
passo "partida e salas no servico Partidas"
sql_partidas "SELECT id, status, motivo, sala_id FROM partidas WHERE jogador_brancas IN ('$A','$B')"
sql_partidas "SELECT count(*) AS salas_ocupadas FROM salas WHERE partida_id IS NOT NULL"
passo "notificacoes de $A"; curl -s "$RANKING/notificacoes/$A"; echo

passo "voltando o alocador ao normal"
docker compose up -d --no-deps alocador-sim
