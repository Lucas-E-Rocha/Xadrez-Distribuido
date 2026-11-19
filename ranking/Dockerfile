# Contexto de build: raiz do repositorio (ver docker-compose.yml)
FROM node:22-alpine
WORKDIR /app
COPY ranking/package.json ./package.json
RUN npm install --omit=dev --no-audit --no-fund
COPY contracts ./contracts
COPY node-common ./node-common
COPY ranking/src ./ranking/src
ENV NODE_ENV=production SERVICE_NAME=ranking
EXPOSE 3000
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -qO- http://localhost:3000/health || exit 1
CMD ["node", "ranking/src/index.js"]
