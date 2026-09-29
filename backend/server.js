'use strict';

const express = require('express');
const path = require('node:path');
const fs = require('node:fs');
const crypto = require('node:crypto');
const multer = require('multer');
const db = require('./db');
const config = require('./config');
const m3u = require('./m3u');
const epg = require('./epg');
const catalogo = require('./catalogo');

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

const REDES_VALIDAS = new Set(['wifi', 'ethernet', 'celular', 'desconhecida']);

// Número de canal estilo TV digital: inteiro puro ("5") ou major.minor
// ("2.1", "10.2"). Guardado como string; sem casas decimais fantasma.
const NUMERO_CANAL_RE = /^\d+(\.\d+)?$/;

/** Valida/normaliza o número de canal enviado pelo painel. Devolve `undefined` em caso de erro. */
function normalizarNumeroCanal(valor) {
  if (valor === undefined) return null; // campo ausente: mantém como estava (PUT) ou fica nulo (POST)
  if (valor === null || String(valor).trim() === '') return null;
  const texto = String(valor).trim();
  if (!NUMERO_CANAL_RE.test(texto)) return undefined;
  return texto;
}

/** Ordena canais pelo número no estilo TV digital (2, 2.1, 2.2, 3, 10 — não alfabético). */
function ordenarPorNumero(canais) {
  function chave(numero) {
    if (numero === null || numero === undefined || String(numero).trim() === '') return null;
    const [majorTexto, minorTexto] = String(numero).split('.');
    const major = Number(majorTexto);
    const minor = minorTexto !== undefined ? Number(minorTexto) : 0;
    if (Number.isNaN(major) || Number.isNaN(minor)) return null;
    return [major, minor];
  }
  return [...canais].sort((a, b) => {
    const ca = chave(a.numero);
    const cb = chave(b.numero);
    if (ca === null && cb === null) return a.id - b.id;
    if (ca === null) return 1; // nulos/inválidos por último
    if (cb === null) return -1;
    if (ca[0] !== cb[0]) return ca[0] - cb[0];
    if (ca[1] !== cb[1]) return ca[1] - cb[1];
    return a.id - b.id;
  });
}

function baseUrl(req) {
  return process.env.PUBLIC_BASE_URL || `${req.protocol}://${req.get('host')}`;
}

// ---------------------------------------------------------------------
// Eventos em tempo real (SSE) — avisa os apps já rodando assim que algo
// muda no painel (canal novo/editado, EPG associado, aparelho autorizado/
// revogado/bloqueado), em vez de esperar a próxima sincronização
// periódica. Unidirecional (servidor -> app), sem token: é só um aviso
// pra "vá checar de novo", o /sincronizar continua sendo a fonte da
// verdade e filtra por device_id normalmente.
// ---------------------------------------------------------------------
const clientesEventos = new Set();

function avisarClientes(motivo) {
  for (const res of clientesEventos) {
    try {
      res.write(`data: ${motivo}\n\n`);
    } catch {
      clientesEventos.delete(res);
    }
  }
}

app.get('/eventos', (req, res) => {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });
  res.write(': conectado\n\n');
  clientesEventos.add(res);

  const ping = setInterval(() => {
    try {
      res.write(': ping\n\n');
    } catch {
      clearInterval(ping);
      clientesEventos.delete(res);
    }
  }, 20_000);

  req.on('close', () => {
    clearInterval(ping);
    clientesEventos.delete(res);
  });
});

// ---------------------------------------------------------------------
// Endpoint que o app Portonet TV chama no boot e periodicamente.
// Contrato completo em claude/analise-saimotv-e-proposta-portonet.md do
// projeto — seção 7. Aqui é a implementação real desse contrato.
// ---------------------------------------------------------------------
app.get('/sincronizar', (req, res) => {
  const inicio = Date.now();
  const deviceId = String(req.query.device_id || '').trim();

  if (!UUID_V4.test(deviceId)) {
    return res.status(400).json({ status: 'erro', motivo: 'device_id ausente ou inválido' });
  }

  const redeInformada = String(req.query.rede || '').trim().toLowerCase();
  const rede = REDES_VALIDAS.has(redeInformada) ? redeInformada : 'desconhecida';

  // Canal atualmente sintonizado — só vem do PlayerActivity (a tela de
  // bloqueio ainda não tem canal nenhum), então totalmente opcional.
  const canalNumero = String(req.query.canal_numero || '').trim().slice(0, 20) || null;
  const canalNome = String(req.query.canal_nome || '').trim().slice(0, 200) || null;

  const existente = db.prepare('SELECT * FROM devices WHERE device_id = ?').get(deviceId);
  if (existente) {
    db.prepare(
      'UPDATE devices SET ultima_sincronizacao = ?, total_sincronizacoes = total_sincronizacoes + 1, rede = ?, canal_atual_numero = ?, canal_atual_nome = ? WHERE device_id = ?'
    ).run(agoraISO(), rede, canalNumero, canalNome, deviceId);
  } else {
    db.prepare(
      'INSERT INTO devices (device_id, autorizado, primeira_vez, ultima_sincronizacao, total_sincronizacoes, rede, canal_atual_numero, canal_atual_nome) VALUES (?, 0, ?, ?, 1, ?, ?, ?)'
    ).run(deviceId, agoraISO(), agoraISO(), rede, canalNumero, canalNome);
  }

  function finalizar(status, extra) {
    registrarAcesso(deviceId, status, req);
    db.prepare('UPDATE devices SET latencia_ms = ? WHERE device_id = ?').run(Date.now() - inicio, deviceId);
    return res.json({ status, ...extra });
  }

  const autorizado = existente ? existente.autorizado === 1 : false;
  if (!autorizado) {
    const mensagem = existente?.mensagem_bloqueio || null;
    return finalizar('nao_autorizado', mensagem ? { mensagem } : {});
  }

  const usarCatalogoProprio = catalogo.temCatalogo();
  const cfg = config.ler();
  const base = baseUrl(req);

  const playlistUrl = usarCatalogoProprio ? `${base}/playlist.m3u8` : cfg.playlist_url;
  const epgUrl = usarCatalogoProprio ? `${base}/epg.xml` : cfg.epg_url;

  if (!playlistUrl || !epgUrl) {
    // Autorizado, mas ainda não há canal cadastrado nem playlist/EPG legado
    // configurado — não faz sentido devolver "autorizado" sem conteúdo.
    return finalizar('nao_autorizado', {});
  }

  return finalizar('autorizado', {
    playlist_url: playlistUrl,
    epg_url: epgUrl,
    playlist_ttl_seconds: cfg.playlist_ttl_seconds,
    epg_ttl_seconds: cfg.epg_ttl_seconds,
    sync_interval_seconds: cfg.sync_interval_seconds,
    tema_cor_primaria: cfg.tema_cor_primaria,
    tema_cor_destaque: cfg.tema_cor_destaque,
    tema_logo_url: cfg.tema_logo_url,
  });
});

// ---------------------------------------------------------------------
// Exportação pública: a playlist e o EPG que o app efetivamente baixa,
// remontados a partir do catálogo de canais cadastrado no painel — não
// mais uma URL externa repassada direto (aí sim uma mudança de tvg-id ou
// uma instabilidade da fonte não quebra o app na hora).
// ---------------------------------------------------------------------
app.get('/playlist.m3u8', (_req, res) => {
  const canais = catalogo.canaisAtivosComFontes();
  res.type('application/vnd.apple.mpegurl').send(m3u.montar(canais));
});

app.get('/epg.xml', async (_req, res) => {
  try {
    const canais = catalogo.canaisComEpgAssociado();
    const xml = await epg.montarExportacao(canais);
    res.type('application/xml').send(xml);
  } catch (erro) {
    res.status(500).json({ erro: erro.message });
  }
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
  const info = db.prepare('UPDATE devices SET autorizado = 1, mensagem_bloqueio = NULL WHERE device_id = ?').run(req.params.id);
  if (info.changes === 0) return res.status(404).json({ erro: 'aparelho não encontrado' });
  avisarClientes('dispositivos');
  res.json({ ok: true });
});

admin.post('/devices/:id/revogar', (req, res) => {
  const info = db.prepare('UPDATE devices SET autorizado = 0, mensagem_bloqueio = NULL WHERE device_id = ?').run(req.params.id);
  if (info.changes === 0) return res.status(404).json({ erro: 'aparelho não encontrado' });
  avisarClientes('dispositivos');
  res.json({ ok: true });
});

// Bloqueio explícito, com mensagem — diferente de "revogar" (que só tira a
// autorização sem explicar o motivo), isso devolve uma mensagem própria
// pro app mostrar na tela de bloqueio ("fale com o suporte", etc).
const MENSAGEM_BLOQUEIO_PADRAO = 'Equipamento bloqueado. Entre em contato com o suporte técnico.';

admin.post('/devices/:id/bloquear', (req, res) => {
  const mensagem = String(req.body?.mensagem || '').trim().slice(0, 300) || MENSAGEM_BLOQUEIO_PADRAO;
  const info = db.prepare('UPDATE devices SET autorizado = 0, mensagem_bloqueio = ? WHERE device_id = ?').run(mensagem, req.params.id);
  if (info.changes === 0) return res.status(404).json({ erro: 'aparelho não encontrado' });
  avisarClientes('dispositivos');
  res.json({ ok: true, mensagem });
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
// Catálogo de canais: importar de uma lista M3U (com pré-visualização —
// o admin escolhe o que trazer, sem gerar de novo os 313 mil "canais" de
// um dump de revendedor) ou cadastrar manualmente, e associar cada um a
// um id de canal dentro de uma entrada de EPG cadastrada à parte.
// ---------------------------------------------------------------------
function canalComFontes(id) {
  const canal = db.prepare('SELECT * FROM canais WHERE id = ?').get(id);
  if (!canal) return null;
  const fontes = db.prepare('SELECT * FROM fontes WHERE canal_id = ? ORDER BY ordem, id').all(id);
  return { ...canal, ativo: canal.ativo === 1, fontes };
}

admin.get('/canais', (_req, res) => {
  const canais = db.prepare('SELECT * FROM canais').all();
  const fontesStmt = db.prepare('SELECT * FROM fontes WHERE canal_id = ? ORDER BY ordem, id');
  const ordenados = ordenarPorNumero(canais);
  res.json(ordenados.map((c) => ({ ...c, ativo: c.ativo === 1, fontes: fontesStmt.all(c.id) })));
});

admin.post('/canais', (req, res) => {
  const { nome, numero, logo, grupo, url } = req.body || {};
  if (!nome || !String(nome).trim()) return res.status(400).json({ erro: 'nome é obrigatório' });
  if (!url || !String(url).trim()) return res.status(400).json({ erro: 'url (fonte do canal) é obrigatória' });

  const numeroValido = normalizarNumeroCanal(numero);
  if (numeroValido === undefined) {
    return res.status(400).json({ erro: 'número de canal inválido — use um inteiro ("5") ou major.minor ("2.1")' });
  }

  const info = db.prepare(
    'INSERT INTO canais (nome, numero, logo, grupo, ativo, criado_em) VALUES (?, ?, ?, ?, 1, ?)'
  ).run(String(nome).trim(), numeroValido, logo || null, grupo || null, agoraISO());
  db.prepare('INSERT INTO fontes (canal_id, url, ordem) VALUES (?, ?, 0)').run(info.lastInsertRowid, String(url).trim());

  avisarClientes('catalogo');
  res.json(canalComFontes(info.lastInsertRowid));
});

admin.put('/canais/:id', (req, res) => {
  const canal = db.prepare('SELECT id FROM canais WHERE id = ?').get(req.params.id);
  if (!canal) return res.status(404).json({ erro: 'canal não encontrado' });

  const { nome, numero, logo, grupo, ativo } = req.body || {};
  const numeroValido = normalizarNumeroCanal(numero);
  if (numeroValido === undefined) {
    return res.status(400).json({ erro: 'número de canal inválido — use um inteiro ("5") ou major.minor ("2.1")' });
  }

  db.prepare(
    'UPDATE canais SET nome = COALESCE(?, nome), numero = ?, logo = ?, grupo = ?, ativo = ? WHERE id = ?'
  ).run(
    nome ? String(nome).trim() : null,
    numeroValido,
    logo === undefined ? null : logo,
    grupo === undefined ? null : grupo,
    ativo === false ? 0 : 1,
    req.params.id
  );
  avisarClientes('catalogo');
  res.json(canalComFontes(req.params.id));
});

admin.delete('/canais/:id', (req, res) => {
  db.prepare('DELETE FROM canais WHERE id = ?').run(req.params.id);
  avisarClientes('catalogo');
  res.json({ ok: true });
});

admin.post('/canais/:id/fontes', (req, res) => {
  const { url } = req.body || {};
  if (!url || !String(url).trim()) return res.status(400).json({ erro: 'url é obrigatória' });
  const canal = db.prepare('SELECT id FROM canais WHERE id = ?').get(req.params.id);
  if (!canal) return res.status(404).json({ erro: 'canal não encontrado' });

  const maxOrdem = db.prepare('SELECT COALESCE(MAX(ordem), -1) AS m FROM fontes WHERE canal_id = ?').get(req.params.id);
  db.prepare('INSERT INTO fontes (canal_id, url, ordem) VALUES (?, ?, ?)').run(req.params.id, String(url).trim(), maxOrdem.m + 1);
  avisarClientes('catalogo');
  res.json(canalComFontes(req.params.id));
});

admin.delete('/canais/:canalId/fontes/:fonteId', (req, res) => {
  db.prepare('DELETE FROM fontes WHERE id = ? AND canal_id = ?').run(req.params.fonteId, req.params.canalId);
  avisarClientes('catalogo');
  res.json(canalComFontes(req.params.canalId));
});

admin.post('/canais/:id/associar-epg', (req, res) => {
  const { epg_input_id, epg_canal_id } = req.body || {};
  const canal = db.prepare('SELECT id FROM canais WHERE id = ?').get(req.params.id);
  if (!canal) return res.status(404).json({ erro: 'canal não encontrado' });

  db.prepare('UPDATE canais SET epg_input_id = ?, epg_canal_id = ? WHERE id = ?').run(
    epg_input_id || null,
    epg_canal_id || null,
    req.params.id
  );
  avisarClientes('catalogo');
  res.json(canalComFontes(req.params.id));
});

// Importação de M3U em duas etapas: (1) buscar+parsear e devolver a lista
// pro admin escolher, sem gravar nada ainda; (2) gravar só os escolhidos.
admin.post('/canais/importar-m3u', async (req, res) => {
  const url = String(req.body?.url || '').trim();
  if (!url) return res.status(400).json({ erro: 'url é obrigatória' });

  try {
    const resposta = await fetch(url, { headers: { 'User-Agent': 'PortonetTV-API/1.0' } });
    if (!resposta.ok) return res.status(400).json({ erro: `Não consegui baixar a lista (HTTP ${resposta.status})` });
    const texto = await resposta.text();
    const entradas = m3u.parse(texto);

    const LIMITE = 3000;
    const truncada = entradas.length > LIMITE;
    res.json({
      total_encontrado: entradas.length,
      truncada,
      itens: entradas.slice(0, LIMITE),
      aviso: truncada
        ? `A lista tem ${entradas.length} itens — mostrando só os primeiros ${LIMITE}. Prefira uma lista já filtrada pros canais que a Portonet realmente vai oferecer.`
        : undefined,
    });
  } catch (erro) {
    const causa = erro.cause?.message || erro.cause?.code || erro.message;
    res.status(400).json({ erro: `Falha ao buscar/ler a lista: ${causa}` });
  }
});

admin.post('/canais/importar-selecionados', (req, res) => {
  const itens = Array.isArray(req.body?.itens) ? req.body.itens.slice(0, 2000) : [];
  if (itens.length === 0) return res.status(400).json({ erro: 'nenhum item selecionado' });

  const inserirCanal = db.prepare('INSERT INTO canais (nome, logo, grupo, ativo, criado_em) VALUES (?, ?, ?, 1, ?)');
  const inserirFonte = db.prepare('INSERT INTO fontes (canal_id, url, ordem) VALUES (?, ?, 0)');

  let importados = 0;
  for (const item of itens) {
    const nome = String(item.tvgName || item.nome || '').trim();
    const url = String(item.url || '').trim();
    if (!nome || !url) continue;
    const info = inserirCanal.run(nome, item.tvgLogo || item.logo || null, item.grupo || null, agoraISO());
    inserirFonte.run(info.lastInsertRowid, url);
    importados++;
  }

  if (importados > 0) avisarClientes('catalogo');
  res.json({ ok: true, importados });
});

// ---------------------------------------------------------------------
// Entradas de EPG: fontes XMLTV cadastradas à parte, que os canais do
// catálogo se associam pra herdar a programação.
// ---------------------------------------------------------------------
admin.get('/epg-inputs', (_req, res) => {
  res.json(db.prepare('SELECT * FROM epg_inputs ORDER BY nome').all());
});

admin.post('/epg-inputs', (req, res) => {
  const { nome, url } = req.body || {};
  if (!nome || !String(nome).trim()) return res.status(400).json({ erro: 'nome é obrigatório' });
  if (!url || !String(url).trim()) return res.status(400).json({ erro: 'url é obrigatória' });

  const info = db.prepare(
    'INSERT INTO epg_inputs (nome, url, status, criado_em) VALUES (?, ?, ?, ?)'
  ).run(String(nome).trim(), String(url).trim(), 'nunca_sincronizado', agoraISO());
  res.json(db.prepare('SELECT * FROM epg_inputs WHERE id = ?').get(info.lastInsertRowid));
});

admin.delete('/epg-inputs/:id', (req, res) => {
  db.prepare('DELETE FROM epg_inputs WHERE id = ?').run(req.params.id);
  res.json({ ok: true });
});

admin.post('/epg-inputs/:id/atualizar', async (req, res) => {
  const input = db.prepare('SELECT * FROM epg_inputs WHERE id = ?').get(req.params.id);
  if (!input) return res.status(404).json({ erro: 'entrada de EPG não encontrada' });

  try {
    const dados = await epg.atualizar(input.id, input.url);
    db.prepare(
      'UPDATE epg_inputs SET status = ?, total_canais_encontrados = ?, ultima_atualizacao = ?, ultimo_erro = NULL WHERE id = ?'
    ).run('ok', dados.canais.size, agoraISO(), input.id);
    res.json(db.prepare('SELECT * FROM epg_inputs WHERE id = ?').get(input.id));
  } catch (erro) {
    db.prepare('UPDATE epg_inputs SET status = ?, ultimo_erro = ? WHERE id = ?').run('erro', erro.message, input.id);
    res.status(400).json(db.prepare('SELECT * FROM epg_inputs WHERE id = ?').get(input.id));
  }
});

admin.get('/epg-inputs/:id/canais', async (req, res) => {
  const input = db.prepare('SELECT * FROM epg_inputs WHERE id = ?').get(req.params.id);
  if (!input) return res.status(404).json({ erro: 'entrada de EPG não encontrada' });

  try {
    const canais = await epg.listarCanaisDoInput(input.id, input.url, req.query.forcar === '1');
    res.json({ canais });
  } catch (erro) {
    res.status(400).json({ erro: erro.message });
  }
});

// ---------------------------------------------------------------------
// Upload de playlist M3U/M3U8 ou EPG XMLTV pelo painel — evita depender
// de hospedar o arquivo em outro lugar pra colar a URL na config. O
// arquivo enviado vira uma URL própria em /arquivos/<nome>.
// ---------------------------------------------------------------------
const EXTENSOES_PERMITIDAS = new Set(['.m3u', '.m3u8', '.xml', '.png', '.jpg', '.jpeg', '.svg', '.webp']);

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
