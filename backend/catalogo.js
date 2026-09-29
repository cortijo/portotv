'use strict';

const db = require('./db');

/** Canais ativos com suas fontes — usado pra gerar a playlist exportada. */
function canaisAtivosComFontes() {
  const canais = db.prepare('SELECT * FROM canais WHERE ativo = 1 ORDER BY COALESCE(numero, id), id').all();
  const fontesStmt = db.prepare('SELECT * FROM fontes WHERE canal_id = ? ORDER BY ordem, id');
  return canais
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
