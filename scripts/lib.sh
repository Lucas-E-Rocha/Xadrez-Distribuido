#!/usr/bin/env bash
# Funcoes usadas pelos scripts de demonstracao. Rode os scripts a partir da raiz do projeto
# (no Windows, use o Git Bash).
set -uo pipefail

API="${API:-http://localhost:8080}"
CARTEIRA="${CARTEIRA:-http://localhost:3001}"
RANKING="${RANKING:-http://localhost:3010}"

titulo() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
passo()  { printf '\033[1;33m-> %s\033[0m\n' "$*"; }

sql_partidas() { docker compose exec -T db-partidas psql -U partidas -d partidas -c "$1"; }
sql_carteira() { docker compose exec -T db-carteira psql -U carteira -d carteira -c "$1"; }
sql_ranking()  { docker compose exec -T db-ranking  psql -U ranking  -d ranking  -c "$1"; }
aws_cli()      { MSYS_NO_PATHCONV=1 docker compose run --rm -T awscli "$@"; }

saldo() { curl -s "$CARTEIRA/carteiras/$1"; echo; }

entrar_fila() { # jogador aposta -> ticketId
  curl -s -X POST "$API/api/fila" -H 'Content-Type: application/json' \
    -d "{\"jogadorId\":\"$1\",\"aposta\":$2}" | sed -E 's/.*"ticketId":"([^"]+)".*/\1/'
}

ticket() { curl -s "$API/api/fila/$1"; echo; }

esperar_status_ticket() { # ticketId timeoutSeg
  local fim=$((SECONDS + ${2:-30}))
  while (( SECONDS < fim )); do
    local t; t=$(curl -s "$API/api/fila/$1")
    if echo "$t" | grep -qE '"status":"(CANCELADO|RECUSADO|CONCLUIDO)"|"statusPartida":"EM_ANDAMENTO"'; then
      echo "$t"; return 0
    fi
    sleep 1
  done
  curl -s "$API/api/fila/$1"; echo
}

topic_arn() { # tipo -> ARN do topico
  aws_cli sns list-topics --query "Topics[?ends_with(TopicArn, ':xadrez-$1')].TopicArn" --output text | tr -d '\r'
}

queue_url() { aws_cli sqs get-queue-url --queue-name "$1" --query QueueUrl --output text | tr -d '\r'; }

uuid() { printf '%04x%04x-%04x-4%03x-a%03x-%04x%04x%04x\n' $RANDOM $RANDOM $RANDOM $((RANDOM % 4096)) $((RANDOM % 4096)) $RANDOM $RANDOM $RANDOM; }

# Encerra (por desistencia) as partidas em andamento cujos jogadores comecam com o prefixo dado,
# para liberar as salas depois de um teste.
desistir_em_andamento() {
  local linhas
  linhas=$(docker compose exec -T db-partidas psql -U partidas -d partidas -t -A -F' ' -c \
    "SELECT id, jogador_brancas FROM partidas WHERE status IN ('EM_ANDAMENTO') AND jogador_brancas LIKE '$1%'" | tr -d '\r')
  [[ -z "$linhas" ]] && return 0
  while read -r id brancas; do
    [[ -z "$id" ]] && continue
    curl -s -o /dev/null -X POST "$API/api/partidas/$id/desistir" -H 'Content-Type: application/json' -d "{\"jogadorId\":\"$brancas\"}"
  done <<< "$linhas"
  passo "partidas de teste encerradas (salas liberadas)"
}

salas_livres() {
  docker compose exec -T db-partidas psql -U partidas -d partidas -t -A -c "SELECT count(*) FROM salas WHERE partida_id IS NULL" | tr -d '\r '
}
