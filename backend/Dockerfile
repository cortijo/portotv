FROM node:22-slim

WORKDIR /app

COPY package.json ./
RUN npm install --omit=dev

COPY . .

ENV PORT=9966
ENV DATA_DIR=/data
VOLUME ["/data"]

EXPOSE 9966

CMD ["node", "server.js"]
