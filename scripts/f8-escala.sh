#!/usr/bin/env bash
# F8 - Escala horizontal: 3 replicas da Carteira.
# Esperado: carga dividida entre as replicas, sem efeito duplicado.
source "$(dirname "$0")/lib.sh"
S=$(date +%s)

titulo "F8 - Carteira com 3 replicas"
docker compose up -d --no-deps --scale carteira=3 carteira
TAXA_FALHA=0 docker compose up -d --no-deps alocador-sim
sleep 8
docker compose ps carteira

PARES=$(salas_livres); (( PARES > 8 )) && PARES=8
passo "$PARES partidas em paralelo"
for i in $(seq 1 "$PARES"); do
  entrar_fila "f8a-$S-$i" 5 >/dev/null & entrar_fila "f8b-$S-$i" 5 >/dev/null &
done
wait
sleep 10
passo "encerrando as partidas (gera PartidaFinalizada para a Carteira pagar)"
desistir_em_andamento "f8"
sleep 6

passo "quantos lancamentos cada replica fez"
sql_carteira "SELECT replica, count(*) FROM lancamentos WHERE jogador_id LIKE 'f8%-$S-%' GROUP BY replica"
passo "efeitos duplicados (precisa ser 0)"
sql_carteira "SELECT count(*) AS duplicados FROM (SELECT partida_id, jogador_id, tipo FROM lancamentos WHERE jogador_id LIKE 'f8%-$S-%' GROUP BY 1,2,3 HAVING count(*) > 1) d"
docker compose up -d --no-deps alocador-sim
