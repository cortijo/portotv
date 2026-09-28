package br.com.portonet.tv

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale

data class Programme(
    val title: String,
    val start: Long,
    val stop: Long,
    val description: String? = null,
) {
    fun isOnAir(now: Long) = start <= now && stop > now
    fun progress(now: Long): Float =
        if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}

/**
 * Motor de EPG a partir do XMLTV próprio da Portonet. Casa por `tvg-id`
 * (channel/@id do XMLTV = tvg-id do M3U) — diferente do SaimoTV-Android, que
 * casa por nome normalizado porque depende de feeds públicos de terceiros
 * sem tvg-id garantido. Com XMLTV próprio da operadora, o id é confiável e o
 * casamento por nome nem é necessário.
 *
 * Parseado em streaming (XmlPullParser), nunca com o XML inteiro em memória
 * de uma vez — importante porque este app roda em TV Box, inclusive modelos
 * fracos com pouca RAM.
 */
object Epg {

    private val FORMATOS = listOf(
        SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US),
        SimpleDateFormat("yyyyMMddHHmmss", Locale.US),
    )

    private fun parseData(raw: String): Long? {
        val valor = raw.trim()
        for (formato in FORMATOS) {
            runCatching { return formato.parse(valor)?.time }
        }
        return null
    }

    /** channelId (tvg-id) -> lista de programas, em ordem cronológica. */
    fun parse(xmltv: String): Map<String, List<Programme>> {
        val porCanal = HashMap<String, MutableList<Programme>>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xmltv))

        var evento = parser.eventType
        while (evento != XmlPullParser.END_DOCUMENT) {
            if (evento == XmlPullParser.START_TAG && parser.name == "programme") {
                val channelId = parser.getAttributeValue(null, "channel")
                val start = parser.getAttributeValue(null, "start")?.let(::parseData)
                val stop = parser.getAttributeValue(null, "stop")?.let(::parseData)
                var titulo: String? = null
                var descricao: String? = null

                var profundidade = 1
                while (profundidade > 0) {
                    evento = parser.next()
                    when (evento) {
                        XmlPullParser.START_TAG -> {
                            profundidade++
                            when (parser.name) {
                                "title" -> titulo = (titulo ?: "") + parser.nextText()
                                "desc" -> descricao = (descricao ?: "") + parser.nextText()
                            }
                            profundidade-- // nextText já consumiu até o end-tag correspondente.
                        }
                        XmlPullParser.END_TAG -> profundidade--
                        XmlPullParser.END_DOCUMENT -> profundidade = 0
                    }
                }

                if (channelId != null && start != null && stop != null && !titulo.isNullOrBlank()) {
                    porCanal.getOrPut(channelId) { mutableListOf() } += Programme(titulo.trim(), start, stop, descricao?.trim())
                }
            }
            evento = parser.next()
        }

        return porCanal.mapValues { (_, lista) -> lista.sortedBy { it.start } }
    }

    fun nowNext(porCanal: Map<String, List<Programme>>, tvgId: String?, now: Long): Pair<Programme, Programme?>? {
        val lista = tvgId?.let { porCanal[it] } ?: return null
        val indice = lista.indexOfFirst { it.isOnAir(now) }
        if (indice < 0) return null
        return lista[indice] to lista.getOrNull(indice + 1)
    }
}
