'use strict';

const express = require('express');
const path = require('node:path');
const fs = require('node:fs');
const crypto = require('node:crypto');
const multer = require('multer');
const db = require('./db');
const config = require('./config');

const app = express();
app.set('trust proxy', true);
app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

const PORT = process.env.PORT || 9966;
const ADMIN_TOKEN = process.env.ADMIN_TOKEN || 'portonet';
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

// Arquivos enviados pelo painel (playlist M3U/M3U8, EPG XMLTV) — servidos
// estaticamente em /arquivos/<nome>, para virar a própria playlist_url/epg_url.
const UPLOADS_DIR = process.env.UPLOADS_DIR || path.join(__dirname, 'data', 'uploads');
fs.mkdirSync(UPLOADS_DIR, { recursive: true });
app.use('/arquivos', express.static(UPLOADS_DIR, { maxAge: 0 }));

function agoraISO() {
  return new Date().toISOString();
}

function registrarAcesso(deviceId, status, req) {
  db.prepare(
    'INSERT INTO acessos (device_id, status, ip, user_agent, quando) VALUES (?, ?, ?, ?, ?)'
  ).run(deviceId, status, req.ip || '', String(req.get('user-agent') || '').slice(0, 300), agoraISO());

  // Poda leve pra não deixar a tabela crescer sem limite num aparelho que
  // sincroniza a cada 30 min por anos — mantém as últimas ~50 mil linhas.
  if (Math.random() < 0.01) {
    db.exec(`
      DELETE FROM acessos WHERE id NOT IN (
        SELECT id FROM acessos ORDER BY id DESC LIMIT 50000
      )
    `);
  }
}

// ---------------------------------------------------------------------
// Endpoint que o app Portonet TV chama no boot e periodicamente.
// Contrato completo em claude/analise-saimotv-e-proposta-portonet.md do
// projeto — seção 7. Aqui é a implementação real desse contrato.
// ---------------------------------------------------------------------
app.get('/sincronizar', (req, res) => {
  const deviceId = String(req.query.device_id || '').trim();

  if (!UUID_V4.test(deviceId)) {
    return res.status(400).json({ status: 'erro', motivo: 'device_id ausente ou inválido' });
  }

  const existente = db.prepare('SELECT * FROM devices WHERE device_id = ?').get(deviceId);
  if (existente) {
    db.prepare(
      'UPDATE devices SET ultima_sincronizacao = ?, total_sincronizacoes = total_sincronizacoes + 1 WHERE device_id = ?'
    ).run(agoraISO(), deviceId);
  } else {
    db.prepare(
      'INSERT INTO devices (device_id, autorizado, primeira_vez, ultima_sincronizacao, total_sincronizacoes) VALUES (?, 0, ?, ?, 1)'
    ).run(deviceId, agoraISO(), agoraISO());
  }

  const autorizado = existente ? existente.autorizado === 1 : false;
  if (!autorizado) {
    registrarAcesso(deviceId, 'nao_autorizado', req);
    return res.json({ status: 'nao_autorizado' });
  }

  const cfg = config.ler();
  if (!cfg.playlist_url || !cfg.epg_url) {
    // Autorizado, mas a Portonet ainda não configurou a playlist/EPG no
    // painel admin — não faz sentido devolver "autorizado" sem conteúdo.
    registrarAcesso(deviceId, 'sem_conteudo', req);
    return res.json({ status: 'nao_autorizado' });
  }

  registrarAcesso(deviceId, 'autorizado', req);
  return res.json({
    status: 'autorizado',
    playlist_url: cfg.playlist_url,
    epg_url: cfg.epg_url,
    playlist_ttl_seconds: cfg.playlist_ttl_seconds,
    epg_ttl_seconds: cfg.epg_ttl_seconds,
    sync_interval_seconds: cfg.sync_interval_seconds,
  });
});

app.get('/health', (_req, res) => res.json({ ok: true }));

// ---------------------------------------------------------------------
// Painel admin — autorizar/revogar aparelhos e configurar playlist/EPG.
// Protegido por um token simples (cabeçalho X-Admin-Token), pensado para
// o time de suporte da Portonet usar pela tela, não pela API direto.
// ---------------------------------------------------------------------
function exigirToken(req, res, next) {
  const token = req.get('X-Admin-Token') || req.query.token;
  if (token !== ADMIN_TOKEN) {
    return res.status(401).json({ erro: 'token inválido' });
  }
  next();
}

const admin = express.Router();
admin.use(exigirToken);

admin.get('/devices', (_req, res) => {
  const linhas = db.prepare('SELECT * FROM devices ORDER BY ultima_sincronizacao DESC').all();
  res.json(linhas.map((l) => ({ ...l, autorizado: l.autorizado === 1 })));
});

admin.post('/devices/:id/autorizar', (req, res) => {
  const info = db.prepare('UPDATE devices SET autorizado = 1 WHERE device_id = ?').run(req.params.id);
  if (info.changes === 0) return res.status(404).json({ erro: 'aparelho não encontrado' });
  res.json({ ok: true });
});

admin.post('/devices/:id/revogar', (req, res) => {
  const info = db.prepare('UPDATE devices SET autorizado = 0 WHERE device_id = ?').run(req.params.id);
  if (info.changes === 0) return res.status(404).json({ erro: 'aparelho não encontrado' });
  res.json({ ok: true });
});

admin.post('/devices/:id/apelido', (req, res) => {
  const apelido = String(req.body?.apelido || '').slice(0, 80);
  db.prepare('UPDATE devices SET apelido = ? WHERE device_id = ?').run(apelido, req.params.id);
  res.json({ ok: true });
});

admin.delete('/devices/:id', (req, res) => {
  db.prepare('DELETE FROM devices WHERE device_id = ?').run(req.params.id);
  res.json({ ok: true });
});

admin.get('/config', (_req, res) => res.json(config.ler()));
admin.post('/config', (req, res) => res.json(config.salvar(req.body || {})));

// ---------------------------------------------------------------------
// Logs de acesso: todo /sincronizar de cada aparelho fica registrado
// aqui (status, IP, quando) — dá pra ver no painel quem sincronizou,
// quando, e se estava autorizado ou não naquele momento.
// ---------------------------------------------------------------------
admin.get('/logs', (req, res) => {
  const deviceId = String(req.query.device_id || '').trim();
  const limite = Math.min(Math.max(Number(req.query.limit) || 200, 1), 2000);

  const linhas = deviceId
    ? db.prepare('SELECT * FROM acessos WHERE device_id = ? ORDER BY id DESC LIMIT ?').all(deviceId, limite)
    : db.prepare('SELECT * FROM acessos ORDER BY id DESC LIMIT ?').all(limite);

  res.json(linhas);
});

// ---------------------------------------------------------------------
// Upload de playlist M3U/M3U8 ou EPG XMLTV pelo painel — evita depender
// de hospedar o arquivo em outro lugar pra colar a URL na config. O
// arquivo enviado vira uma URL própria em /arquivos/<nome>.
// ---------------------------------------------------------------------
const EXTENSOES_PERMITIDAS = new Set(['.m3u', '.m3u8', '.xml']);

const upload = multer({
  storage: multer.diskStorage({
    destination: (_req, _file, cb) => cb(null, UPLOADS_DIR),
    filename: (_req, file, cb) => {
      const ext = path.extname(file.originalname).toLowerCase();
      const nome = `${Date.now()}-${crypto.randomBytes(4).toString('hex')}${ext}`;
      cb(null, nome);
    },
  }),
  limits: { fileSize: 100 * 1024 * 1024 }, // 100 MB — folga pra playlists grandes, mas não ilimitado
  fileFilter: (_req, file, cb) => {
    const ext = path.extname(file.originalname).toLowerCase();
    if (!EXTENSOES_PERMITIDAS.has(ext)) {
      return cb(new Error('Extensão não permitida — envie .m3u, .m3u8 ou .xml'));
    }
    cb(null, true);
  },
});

admin.post('/upload', (req, res) => {
  upload.single('arquivo')(req, res, (erro) => {
    if (erro) return res.status(400).json({ erro: erro.message });
    if (!req.file) return res.status(400).json({ erro: 'nenhum arquivo enviado' });

    const base = process.env.PUBLIC_BASE_URL || `${req.protocol}://${req.get('host')}`;
    res.json({
      ok: true,
      url: `${base}/arquivos/${req.file.filename}`,
      nome_original: req.file.originalname,
      tamanho: req.file.size,
    });
  });
});

app.use('/admin/api', admin);

app.listen(PORT, () => {
  console.log(`Portonet TV API ouvindo na porta ${PORT}`);
  console.log(`Painel admin: http://localhost:${PORT}/admin.html`);
  if (ADMIN_TOKEN === 'portonet') {
    console.log('AVISO: usando o ADMIN_TOKEN padrão ("portonet") — defina a variável de ambiente ADMIN_TOKEN em produção.');
  }
});
