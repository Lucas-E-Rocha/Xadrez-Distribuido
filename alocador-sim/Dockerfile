# Contexto de build: raiz do repositorio (ver docker-compose.yml)
FROM node:22-alpine
WORKDIR /app
COPY alocador-sim/package.json ./package.json
RUN npm install --omit=dev --no-audit --no-fund
COPY contracts ./contracts
COPY node-common ./node-common
COPY alocador-sim/src ./alocador-sim/src
ENV NODE_ENV=production SERVICE_NAME=alocador-sim
EXPOSE 3000
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -qO- http://localhost:3000/ || exit 1
CMD ["node", "alocador-sim/src/index.js"]
