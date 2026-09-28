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

    fun parse(m3u: String): List<Channel> {
        data class Pendente(val tvgId: String?, val name: String, val logo: String?, val group: String?)

        val porChave = LinkedHashMap<String, Channel>()
        var pendente: Pendente? = null

        for (linhaBruta in m3u.lineSequence()) {
            val linha = linhaBruta.trim()
            if (linha.isEmpty()) continue

            if (linha.startsWith("#EXTINF:")) {
                val attrs = ATTR.findAll(linha).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                val nome = linha.substringAfterLast(',').trim().ifBlank { attrs["tvg-name"] ?: "Canal" }
                pendente = Pendente(
                    tvgId = attrs["tvg-id"]?.trim()?.takeIf { it.isNotBlank() },
                    name = nome,
                    logo = attrs["tvg-logo"]?.trim()?.takeIf { it.isNotBlank() },
                    group = attrs["group-title"]?.trim()?.takeIf { it.isNotBlank() },
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
                    number = porChave.size + 1,
                    sources = listOf(Source(linha)),
                )
            }
        }

        // Número fixo por ordem de chegada na lista, 1-based — estável entre
        // sincronizações desde que a Portonet não reordene a playlist.
        return porChave.values.mapIndexed { indice, canal -> canal.copy(number = indice + 1) }
    }
}
