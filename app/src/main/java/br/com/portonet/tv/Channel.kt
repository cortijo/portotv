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
    /** Número estilo TV digital — inteiro puro ("5") ou major.minor ("2.1"), como texto. */
    val number: String,
    val sources: List<Source>,
)

/**
 * Formata o número de canal pra exibição: um número com "." (major.minor,
 * ex. "2.1") aparece como está; um número puro (ex. "5") ganha o zero à
 * esquerda de sempre ("05"), pra manter a estética de listas simples.
 */
fun formatarNumeroCanal(numero: String): String =
    if (numero.contains('.')) numero else numero.padStart(2, '0')
