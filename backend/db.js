'use strict';

const { DatabaseSync } = require('node:sqlite');
const path = require('node:path');
const fs = require('node:fs');

const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
fs.mkdirSync(DATA_DIR, { recursive: true });

const db = new DatabaseSync(path.join(DATA_DIR, 'portonet.db'));

db.exec(`
  CREATE TABLE IF NOT EXISTS devices (
    device_id TEXT PRIMARY KEY,
    autorizado INTEGER NOT NULL DEFAULT 0,
    apelido TEXT,
    primeira_vez TEXT NOT NULL,
    ultima_sincronizacao TEXT NOT NULL,
    total_sincronizacoes INTEGER NOT NULL DEFAULT 0
  );

  CREATE TABLE IF NOT EXISTS config (
    chave TEXT PRIMARY KEY,
    valor TEXT
  );
`);

// Configuração padrão na primeira execução — o time da Portonet troca depois
// pelo painel admin (URLs reais da playlist e do EPG).
const PADRAO = {
  playlist_url: '',
  epg_url: '',
  playlist_ttl_seconds: '21600',
  epg_ttl_seconds: '21600',
  sync_interval_seconds: '1800',
};

const contarConfig = db.prepare('SELECT COUNT(*) AS n FROM config').get();
if (contarConfig.n === 0) {
  const inserir = db.prepare('INSERT INTO config (chave, valor) VALUES (?, ?)');
  for (const [chave, valor] of Object.entries(PADRAO)) {
    inserir.run(chave, valor);
  }
}

module.exports = db;
