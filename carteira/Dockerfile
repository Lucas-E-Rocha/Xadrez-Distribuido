# Contexto de build: raiz do repositorio (ver docker-compose.yml)
FROM node:22-alpine
WORKDIR /app
COPY carteira/package.json ./package.json
RUN npm install --omit=dev --no-audit --no-fund
COPY contracts ./contracts
COPY node-common ./node-common
COPY carteira/src ./carteira/src
ENV NODE_ENV=production SERVICE_NAME=carteira
EXPOSE 3000
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -qO- http://localhost:3000/health || exit 1
CMD ["node", "carteira/src/index.js"]
