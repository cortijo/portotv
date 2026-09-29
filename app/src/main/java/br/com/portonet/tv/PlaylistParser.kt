package br.com.portonet.tv

/**
 * Parser de M3U (extended, `#EXTM3U`/`#EXTINF`) para a lista de canais da
 * Portonet. Só entende o que a lista da operadora precisa publicar —
 * `tvg-id` (para casar com o EPG), `tvg-logo` e `group-title` — nada do
 * formato proprietário `.txt` que o SaimoTV-Android também aceita, porque a
 * Portonet já entrega M3U puro.
 *
 * Duas entradas com o mesmo `tvg-id` (ou, na ausência dele, o mesmo nome)
 * viram fontes alternativas do mesmo canal, não dois canais duplicados —
 * assim a playlist pode publicar mais de um link por canal como redundância.
 */
object PlaylistParser {

    private val ATTR = Regex("""([a-zA-Z0-9_-]+)="([^"]*)"""")

    private val NUMERO_VALIDO = Regex("""^\d+(\.\d+)?$""")

    fun parse(m3u: String): List<Channel> {
        data class Pendente(val tvgId: String?, val name: String, val logo: String?, val group: String?, val numero: String?)

        val porChave = LinkedHashMap<String, Channel>()
        var pendente: Pendente? = null

        for (linhaBruta in m3u.lineSequence()) {
            val linha = linhaBruta.trim()
            if (linha.isEmpty()) continue

            if (linha.startsWith("#EXTINF:")) {
                val attrs = ATTR.findAll(linha).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                val nome = linha.substringAfterLast(',').trim().ifBlank { attrs["tvg-name"] ?: "Canal" }
                val numero = attrs["tvg-chno"]?.trim()?.takeIf { it.isNotBlank() && NUMERO_VALIDO.matches(it) }
                pendente = Pendente(
                    tvgId = attrs["tvg-id"]?.trim()?.takeIf { it.isNotBlank() },
                    name = nome,
                    logo = attrs["tvg-logo"]?.trim()?.takeIf { it.isNotBlank() },
                    group = attrs["group-title"]?.trim()?.takeIf { it.isNotBlank() },
                    numero = numero,
                )
                continue
            }

            if (linha.startsWith("#")) continue // outras diretivas M3U, ignoradas.

            // Linha de URL: fecha a entrada pendente.
            val atual = pendente ?: continue
            pendente = null
            val chave = atual.tvgId ?: atual.name.lowercase()

            val existente = porChave[chave]
            if (existente != null) {
                porChave[chave] = existente.copy(sources = existente.sources + Source(linha))
            } else {
                porChave[chave] = Channel(
                    tvgId = atual.tvgId,
                    name = atual.name,
                    logo = atual.logo,
                    group = atual.group,
                    // Número temporário — vira o de fato logo abaixo, com o
                    // fallback por ordem de chegada pra quem não tem tvg-chno.
                    number = atual.numero ?: "",
                    sources = listOf(Source(linha)),
                )
            }
        }

        // Usa o tvg-chno (número estilo TV digital, ex. "2.1") vindo da
        // playlist quando presente; canal sem tvg-chno cai no número fixo por
        // ordem de chegada na lista, 1-based — estável entre sincronizações
        // desde que a Portonet não reordene a playlist.
        return porChave.values.mapIndexed { indice, canal ->
            if (canal.number.isBlank()) canal.copy(number = (indice + 1).toString()) else canal
        }
    }
}
