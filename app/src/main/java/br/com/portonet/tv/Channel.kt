package br.com.portonet.tv

/**
 * Uma fonte de vídeo dentro de um canal. A maioria dos canais tem uma só;
 * quando a playlist da Portonet publica mais de uma URL para o mesmo
 * `tvg-id`, elas viram fontes alternativas — ver [PlaylistParser].
 */
data class Source(val url: String) {
    val isDash: Boolean get() = url.substringBefore('?').endsWith(".mpd", ignoreCase = true)
    val isHls: Boolean get() = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
    // Sem extensão reconhecida: tratado como MPEG-TS bruto, servido direto por
    // muita lista IPTV sem nenhuma extensão na URL.
}

data class Channel(
    val tvgId: String?,
    val name: String,
    val logo: String?,
    val group: String?,
    val number: Int,
    val sources: List<Source>,
)
