'use strict';

const { XMLParser, XMLBuilder } = require('fast-xml-parser');

/**
 * Leitura e remontagem de XMLTV. Uma "entrada de EPG" cadastrada pelo admin
 * é uma URL de XMLTV de terceiros (ex.: guia público, feed de um agregador).
 * A API busca, guarda em cache por um tempo (CACHE_MS) e extrai:
 *  - a lista de canais que existem *dentro* desse XMLTV (id + nome), pro
 *    admin escolher qual corresponde a qual canal do catálogo;
 *  -, na exportação, só os <programme> dos canais associados, remontados
 *    com o id interno do canal (`portonet-<id>`) — o app nunca vê o
 *    tvg-id original da fonte, então trocar de fonte não quebra nada.
 */

const CACHE_MS = 15 * 60 * 1000;
const cache = new Map(); // epg_input.id -> { buscadoEm, canais: Map<idOriginal, nome>, programasPorCanal: Map<idOriginal, array> }

const parser = new XMLParser({ ignoreAttributes: false, attributeNamePrefix: '@_', textNodeName: '#text' });
const builder = new XMLBuilder({ ignoreAttributes: false, attributeNamePrefix: '@_', textNodeName: '#text', format: false });

function comoArray(valor) {
  if (valor === undefined || valor === null) return [];
  return Array.isArray(valor) ? valor : [valor];
}

async function buscarEProcessar(url) {
  let resposta;
  try {
    resposta = await fetch(url, { headers: { 'User-Agent': 'PortonetTV-API/1.0' } });
  } catch (erro) {
    // undici só devolve "fetch failed" na mensagem principal — a causa real
    // (DNS, conexão recusada, timeout) fica em erro.cause.
    const causa = erro.cause?.message || erro.cause?.code || erro.message;
    throw new Error(`Falha de rede ao buscar "${url}": ${causa}`);
  }
  if (!resposta.ok) throw new Error(`HTTP ${resposta.status} ao buscar o XMLTV em "${url}"`);
  const texto = await resposta.text();

  const doc = parser.parse(texto);
  const tv = doc.tv || {};
  const canaisXml = comoArray(tv.channel);
  const programasXml = comoArray(tv.programme);

  const canais = new Map();
  for (const c of canaisXml) {
    const id = c['@_id'];
    if (!id) continue;
    const nomeNode = comoArray(c['display-name'])[0];
    const nome = typeof nomeNode === 'object' ? nomeNode['#text'] : nomeNode;
    canais.set(id, nome || id);
  }

  const programasPorCanal = new Map();
  for (const p of programasXml) {
    const canalId = p['@_channel'];
    if (!canalId) continue;
    if (!programasPorCanal.has(canalId)) programasPorCanal.set(canalId, []);
    programasPorCanal.get(canalId).push(p);
  }

  return { canais, programasPorCanal, totalPrograms: programasXml.length };
}

/** Garante que o cache desse input esteja atualizado (busca se nunca buscou ou estourou o TTL). */
async function atualizar(epgInputId, url) {
  const processado = await buscarEProcessar(url);
  cache.set(epgInputId, { buscadoEm: Date.now(), ...processado });
  return processado;
}

async function obterOuAtualizar(epgInputId, url) {
  const atual = cache.get(epgInputId);
  if (atual && Date.now() - atual.buscadoEm < CACHE_MS) return atual;
  return atualizar(epgInputId, url);
}

/** Lista {id, nome} dos canais existentes dentro de um input — pro admin associar. */
async function listarCanaisDoInput(epgInputId, url, forcarAtualizacao = false) {
  const dados = forcarAtualizacao ? await atualizar(epgInputId, url) : await obterOuAtualizar(epgInputId, url);
  return [...dados.canais.entries()].map(([id, nome]) => ({ id, nome }));
}

/**
 * Monta o XMLTV final: um <channel> + os <programme> (remapeados pro id
 * interno) de cada canal do catálogo que tem associação de EPG configurada.
 */
async function montarExportacao(canaisComEpg) {
  const canaisSaida = [];
  const programasSaida = [];

  // Agrupa por epg_input pra buscar cada fonte só uma vez.
  const porInput = new Map();
  for (const c of canaisComEpg) {
    if (!c.epg_input_id || !c.epg_canal_id) continue;
    if (!porInput.has(c.epg_input_id)) porInput.set(c.epg_input_id, []);
    porInput.get(c.epg_input_id).push(c);
  }

  for (const [epgInputId, canaisDoInput] of porInput) {
    const url = canaisDoInput[0].epg_input_url;
    let dados;
    try {
      dados = await obterOuAtualizar(epgInputId, url);
    } catch {
      continue; // fonte fora do ar — não derruba a exportação inteira, só fica sem esses canais
    }

    for (const canal of canaisDoInput) {
      const idInterno = `portonet-${canal.id}`;
      canaisSaida.push({
        '@_id': idInterno,
        'display-name': canal.nome,
      });
      const programas = dados.programasPorCanal.get(canal.epg_canal_id) || [];
      for (const p of programas) {
        programasSaida.push({ ...p, '@_channel': idInterno });
      }
    }
  }

  const xmlObj = { tv: { channel: canaisSaida, programme: programasSaida } };
  return '<?xml version="1.0" encoding="UTF-8"?>\n' + builder.build(xmlObj);
}

module.exports = { listarCanaisDoInput, montarExportacao, atualizar };
