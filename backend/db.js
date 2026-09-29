'use strict';

const { DatabaseSync } = require('node:sqlite');
const path = require('node:path');
const fs = require('node:fs');

const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
fs.mkdirSync(DATA_DIR, { recursive: true });

const db = new DatabaseSync(path.join(DATA_DIR, 'portonet.db'));
db.exec('PRAGMA foreign_keys = ON;');

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

  CREATE TABLE IF NOT EXISTS acessos (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    status TEXT NOT NULL,
    ip TEXT,
    user_agent TEXT,
    quando TEXT NOT NULL
  );

  CREATE INDEX IF NOT EXISTS idx_acessos_device ON acessos (device_id);
  CREATE INDEX IF NOT EXISTS idx_acessos_quando ON acessos (quando);

  -- Entradas de EPG (fontes XMLTV cadastradas) — um canal se associa a uma
  -- entrada + ao id de canal *dentro* daquele XMLTV, em vez do app depender
  -- de uma URL de EPG externa direto (frágil: muda o tvg-id lá fora e quebra
  -- tudo aqui). A API guarda essa associação e remonta um XMLTV só com o
  -- que interessa.
  CREATE TABLE IF NOT EXISTS epg_inputs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    nome TEXT NOT NULL,
    url TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'nunca_sincronizado',
    total_canais_encontrados INTEGER NOT NULL DEFAULT 0,
    ultima_atualizacao TEXT,
    ultimo_erro TEXT,
    criado_em TEXT NOT NULL
  );

  -- Canais cadastrados manualmente ou importados de uma lista M3U. É esse
  -- catálogo — não mais uma URL de playlist externa direto — que vira a
  -- playlist que o app baixa.
  CREATE TABLE IF NOT EXISTS canais (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    nome TEXT NOT NULL,
    -- "Número" no estilo TV digital: inteiro puro ("5") ou major.minor
    -- ("2.1", "2.2") — texto porque SQLite não tem tipo decimal fixo e o
    -- major.minor precisa ser preservado como string (2.10 != 2.1). Graças
    -- à afinidade de tipo do SQLite, uma coluna já criada como INTEGER em
    -- instalações antigas continua lendo/gravando esses valores numa boa;
    -- não é preciso migrar linhas existentes.
    numero TEXT,
    logo TEXT,
    grupo TEXT,
    ativo INTEGER NOT NULL DEFAULT 1,
    epg_input_id INTEGER REFERENCES epg_inputs(id) ON DELETE SET NULL,
    epg_canal_id TEXT,
    criado_em TEXT NOT NULL
  );

  -- Uma ou mais URLs de stream por canal (redundância — o app já trata
  -- fontes repetidas do mesmo canal como fallback automático).
  CREATE TABLE IF NOT EXISTS fontes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    canal_id INTEGER NOT NULL REFERENCES canais(id) ON DELETE CASCADE,
    url TEXT NOT NULL,
    ordem INTEGER NOT NULL DEFAULT 0
  );

  CREATE INDEX IF NOT EXISTS idx_fontes_canal ON fontes (canal_id);
`);

// Aparelhos ganharam colunas novas em versões anteriores do banco — soma
// com ALTER TABLE ... IF NOT EXISTS não existe no SQLite, então checa antes.
function garantirColuna(tabela, coluna, definicao) {
  const colunas = db.prepare(`PRAGMA table_info(${tabela})`).all().map((c) => c.name);
  if (!colunas.includes(coluna)) {
    db.exec(`ALTER TABLE ${tabela} ADD COLUMN ${coluna} ${definicao}`);
  }
}

garantirColuna('devices', 'rede', 'TEXT');
garantirColuna('devices', 'latencia_ms', 'INTEGER');
garantirColuna('devices', 'mensagem_bloqueio', 'TEXT');
garantirColuna('devices', 'sinal_dbm', 'INTEGER');
garantirColuna('devices', 'sinal_nivel', 'INTEGER');
garantirColuna('devices', 'cpu_pct', 'INTEGER');
garantirColuna('devices', 'canal_atual_numero', 'TEXT');
garantirColuna('devices', 'canal_atual_nome', 'TEXT');

// Configuração padrão na primeira execução — o time da Portonet troca depois
// pelo painel admin (URLs reais da playlist e do EPG).
const PADRAO = {
  playlist_url: '',
  epg_url: '',
  playlist_ttl_seconds: '21600',
  epg_ttl_seconds: '21600',
  // 2 min em vez de 30 — uma mudança no painel (canal novo, aparelho
  // bloqueado) demora bem menos pra propagar pros TV Boxes já ligados.
  // OBS: PADRAO só semeia a tabela config vazia (primeira execução) — um
  // servidor já em produção mantém o valor que já tem salvo; ajustar esse
  // aqui não muda nada num banco existente, é preciso trocar pelo painel.
  sync_interval_seconds: '120',
  tema_cor_primaria: '#01237C',
  tema_cor_destaque: '#0857FF',
  tema_logo_url: '',
};

const contarConfig = db.prepare('SELECT COUNT(*) AS n FROM config').get();
if (contarConfig.n === 0) {
  const inserir = db.prepare('INSERT INTO config (chave, valor) VALUES (?, ?)');
  for (const [chave, valor] of Object.entries(PADRAO)) {
    inserir.run(chave, valor);
  }
}

module.exports = db;
