'use strict';

const express = require('express');
const path = require('node:path');
const db = require('./db');
const config = require('./config');

const app = express();
app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

const PORT = process.env.PORT || 9966;
const ADMIN_TOKEN = process.env.ADMIN_TOKEN || 'portonet';
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function agoraISO() {
  return new Date().toISOString();
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
    return res.json({ status: 'nao_autorizado' });
  }

  const cfg = config.ler();
  if (!cfg.playlist_url || !cfg.epg_url) {
    // Autorizado, mas a Portonet ainda não configurou a playlist/EPG no
    // painel admin — não faz sentido devolver "autorizado" sem conteúdo.
    return res.json({ status: 'nao_autorizado' });
  }

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

app.use('/admin/api', admin);

app.listen(PORT, () => {
  console.log(`Portonet TV API ouvindo na porta ${PORT}`);
  console.log(`Painel admin: http://localhost:${PORT}/admin.html`);
  if (ADMIN_TOKEN === 'portonet') {
    console.log('AVISO: usando o ADMIN_TOKEN padrão ("portonet") — defina a variável de ambiente ADMIN_TOKEN em produção.');
  }
});
