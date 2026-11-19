#!/usr/bin/env bash
# F5 - Corrida pelo recurso: 100 jogadores ao mesmo tempo para 10 salas.
# Esperado: exatamente 10 partidas (uma por sala); os outros 80 jogadores recusados (SEM_SALA_DISPONIVEL).
source "$(dirname "$0")/lib.sh"
S=$(date +%s)

titulo "F5 - 100 requisicoes simultaneas"
passo "alocador sem falhas, para nenhuma sala ser liberada no meio do teste"
TAXA_FALHA=0 docker compose up -d --no-deps alocador-sim
sleep 3
LIVRES=$(salas_livres)
passo "salas livres antes do teste: $LIVRES"

passo "disparando 100 POST /api/fila em paralelo"
for i in $(seq 1 100); do
  curl -s -o /dev/null -X POST "$API/api/fila" -H 'Content-Type: application/json' -d "{\"jogadorId\":\"f5-$S-$i\",\"aposta\":10}" &
done
wait
sleep 8

passo "resultado"
sql_partidas "SELECT status, count(*) FROM partidas WHERE jogador_brancas LIKE 'f5-$S-%' GROUP BY status"
sql_partidas "SELECT status, motivo, count(*) FROM fila_tickets WHERE jogador_id LIKE 'f5-$S-%' GROUP BY status, motivo"
sql_partidas "SELECT sala_id, count(*) FROM partidas WHERE status NOT IN ('FINALIZADA','CANCELADA') GROUP BY sala_id HAVING count(*) > 1"
echo "   (a consulta acima precisa voltar vazia: nenhuma sala com duas partidas)"
passo "esperado: $LIVRES partidas e $((100 - 2 * LIVRES)) tickets RECUSADO"

passo "limpeza: encerrando as partidas do teste"
desistir_em_andamento "f5-$S-"
docker compose up -d --no-deps alocador-sim
