'use strict';

const db = require('./db');

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
    if (ca === null) return 1;
    if (cb === null) return -1;
    if (ca[0] !== cb[0]) return ca[0] - cb[0];
    if (ca[1] !== cb[1]) return ca[1] - cb[1];
    return a.id - b.id;
  });
}

/** Canais ativos com suas fontes — usado pra gerar a playlist exportada. */
function canaisAtivosComFontes() {
  const canais = db.prepare('SELECT * FROM canais WHERE ativo = 1').all();
  const fontesStmt = db.prepare('SELECT * FROM fontes WHERE canal_id = ? ORDER BY ordem, id');
  return ordenarPorNumero(canais)
    .map((canal) => ({ ...canal, fontes: fontesStmt.all(canal.id) }))
    .filter((canal) => canal.fontes.length > 0); // canal sem nenhuma fonte não deveria aparecer na playlist
}

/** Canais ativos com associação de EPG configurada — usado pra gerar o XMLTV exportado. */
function canaisComEpgAssociado() {
  return db.prepare(`
    SELECT canais.id, canais.nome, canais.epg_input_id, canais.epg_canal_id, epg_inputs.url AS epg_input_url
    FROM canais
    JOIN epg_inputs ON epg_inputs.id = canais.epg_input_id
    WHERE canais.ativo = 1 AND canais.epg_canal_id IS NOT NULL
  `).all();
}

function temCatalogo() {
  const n = db.prepare('SELECT COUNT(*) AS n FROM canais WHERE ativo = 1').get();
  return n.n > 0;
}

module.exports = { canaisAtivosComFontes, canaisComEpgAssociado, temCatalogo };
