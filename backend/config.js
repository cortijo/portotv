'use strict';

const db = require('./db');

const CHAVES = [
  'playlist_url',
  'epg_url',
  'playlist_ttl_seconds',
  'epg_ttl_seconds',
  'sync_interval_seconds',
  // Identidade visual — cores e logo que o app aplica na tela de bloqueio e
  // no guia de programação. Defaults abaixo == cores atuais hardcoded no
  // app, então nada muda visualmente até o time trocar pelo painel.
  'tema_cor_primaria',
  'tema_cor_destaque',
  'tema_logo_url',
];

function ler() {
  const linhas = db.prepare('SELECT chave, valor FROM config').all();
  const mapa = Object.fromEntries(linhas.map((l) => [l.chave, l.valor]));
  return {
    playlist_url: mapa.playlist_url || '',
    epg_url: mapa.epg_url || '',
    playlist_ttl_seconds: Number(mapa.playlist_ttl_seconds) || 21600,
    epg_ttl_seconds: Number(mapa.epg_ttl_seconds) || 21600,
    sync_interval_seconds: Number(mapa.sync_interval_seconds) || 1800,
    tema_cor_primaria: mapa.tema_cor_primaria || '#01237C',
    tema_cor_destaque: mapa.tema_cor_destaque || '#0857FF',
    tema_logo_url: mapa.tema_logo_url || '',
  };
}

function salvar(parcial) {
  const set = db.prepare('INSERT INTO config (chave, valor) VALUES (?, ?) ON CONFLICT(chave) DO UPDATE SET valor = excluded.valor');
  for (const chave of CHAVES) {
    if (parcial[chave] !== undefined && parcial[chave] !== null) {
      set.run(chave, String(parcial[chave]));
    }
  }
  return ler();
}

module.exports = { ler, salvar, CHAVES };
