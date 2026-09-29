'use strict';

/**
 * Parser/gerador de M3U (mesma leitura do PlaylistParser.kt do app, do
 * lado do servidor): usado para (1) importar uma lista externa e deixar o
 * admin escolher o que trazer para o catálogo próprio, e (2) gerar a
 * playlist final que o app baixa, a partir do catálogo (tabelas `canais`
 * e `fontes`) — não mais direto de uma URL externa.
 */

function extrairAtributo(linha, nome) {
  const m = linha.match(new RegExp(`${nome}="([^"]*)"`, 'i'));
  return m ? m[1] : '';
}

/** Lê um texto M3U e devolve uma lista plana de entradas (uma por URL). */
function parse(texto) {
  const linhas = texto.split(/\r?\n/);
  const entradas = [];
  let pendente = null;

  for (const linhaBruta of linhas) {
    const linha = linhaBruta.trim();
    if (!linha) continue;

    if (linha.startsWith('#EXTINF')) {
      const nome = linha.split(',').pop()?.trim() || 'Sem nome';
      pendente = {
        tvgId: extrairAtributo(linha, 'tvg-id'),
        tvgName: extrairAtributo(linha, 'tvg-name') || nome,
        tvgLogo: extrairAtributo(linha, 'tvg-logo'),
        grupo: extrairAtributo(linha, 'group-title'),
        nome,
      };
      continue;
    }

    if (linha.startsWith('#')) continue; // outras tags (#EXTM3U, #EXT-X-*, ...) — ignora

    // Linha de URL — fecha a entrada pendente.
    if (pendente) {
      entradas.push({ ...pendente, url: linha });
      pendente = null;
    }
  }

  return entradas;
}

/** Monta o M3U final a partir do catálogo (canais + fontes) do banco. */
function montar(canaisComFontes) {
  const linhas = ['#EXTM3U'];
  for (const canal of canaisComFontes) {
    const tvgId = `portonet-${canal.id}`;
    for (const fonte of canal.fontes) {
      const atributos = [
        `tvg-id="${tvgId}"`,
        `tvg-name="${escaparAtributo(canal.nome)}"`,
        // Número estilo TV digital ("2.1") passa direto como texto — o app
        // (PlaylistParser.kt) lê tvg-chno como string, sem converter pra Int.
        canal.numero !== null && canal.numero !== undefined && String(canal.numero).trim() !== ''
          ? `tvg-chno="${escaparAtributo(canal.numero)}"`
          : '',
        canal.logo ? `tvg-logo="${escaparAtributo(canal.logo)}"` : '',
        canal.grupo ? `group-title="${escaparAtributo(canal.grupo)}"` : '',
      ].filter(Boolean).join(' ');
      linhas.push(`#EXTINF:-1 ${atributos},${canal.nome}`);
      linhas.push(fonte.url);
    }
  }
  return linhas.join('\n') + '\n';
}

function escaparAtributo(valor) {
  return String(valor || '').replace(/"/g, "'");
}

module.exports = { parse, montar };
