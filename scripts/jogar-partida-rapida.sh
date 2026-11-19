#!/usr/bin/env bash
# Joga uma partida inteira pela API (mate do pastor), para testar o fluxo feliz de ponta a ponta.
source "$(dirname "$0")/lib.sh"
S=$(date +%s); A="ana$S"; B="bia$S"

titulo "Partida completa: $A x $B"
TA=$(entrar_fila "$A" 50); TB=$(entrar_fila "$B" 50)
T=$(esperar_status_ticket "$TA" 30); echo "$T"
PID=$(echo "$T" | sed -E 's/.*"partidaId":"([^"]+)".*/\1/')
EST=$(curl -s "$API/api/partidas/$PID")
BR=$(echo "$EST" | sed -E 's/.*"brancas":"([^"]+)".*/\1/'); PR=$(echo "$EST" | sed -E 's/.*"pretas":"([^"]+)".*/\1/')
lance() { curl -s -X POST "$API/api/partidas/$PID/jogadas" -H 'Content-Type: application/json' -d "{\"jogadorId\":\"$1\",\"origem\":\"$2\",\"destino\":\"$3\"}" | grep -oE '"status":"[A-Z_]+"|"erro":"[^"]+"'; }
lance "$BR" e2 e4; lance "$PR" e7 e5; lance "$BR" f1 c4; lance "$PR" b8 c6
lance "$BR" d1 h5; lance "$PR" g8 f6; lance "$BR" h5 f7
sleep 4
curl -s "$API/api/partidas/$PID" | grep -oE '"(status|vencedor|motivo)":"[^"]*"'
passo "saldos"; saldo "$BR"; saldo "$PR"
passo "ranking"; curl -s "$RANKING/ranking"; echo
passo "historico no S3"; curl -s "$RANKING/historico/$PID" | head -c 400; echo
