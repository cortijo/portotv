package br.com.portonet.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Baixa playlist (M3U) e EPG (XMLTV) das URLs que o [Provisioning] devolveu,
 * parseia e cacheia em disco — o *resultado* processado, seguindo o mesmo
 * princípio do SaimoTV-Android: reabrir o app não deve esperar o parse de
 * novo se a rede estiver lenta.
 */
object ContentRepository {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Volatile var channels: List<Channel> = emptyList()
        private set
    @Volatile var epg: Map<String, List<Programme>> = emptyMap()
        private set

    private fun baixar(url: String): String? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", "PortonetTV/1.0").build()
        client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    suspend fun atualizar(context: Context, autorizado: Sincronizacao.Autorizado) = withContext(Dispatchers.IO) {
        baixar(autorizado.playlistUrl)?.let { m3u ->
            val canais = PlaylistParser.parse(m3u)
            if (canais.isNotEmpty()) {
                channels = canais
                runCatching { File(context.filesDir, "playlist_cache.m3u").writeText(m3u) }
            }
        }
        baixar(autorizado.epgUrl)?.let { xmltv ->
            val guia = Epg.parse(xmltv)
            if (guia.isNotEmpty()) {
                epg = guia
                runCatching { File(context.filesDir, "epg_cache.xml").writeText(xmltv) }
            }
        }
    }

    /** Carrega o que estiver em disco, sem tocar na rede — usado na abertura antes/sem sincronizar. */
    fun carregarCache(context: Context) {
        runCatching { File(context.filesDir, "playlist_cache.m3u").readText() }
            .getOrNull()?.let { channels = PlaylistParser.parse(it) }
        runCatching { File(context.filesDir, "epg_cache.xml").readText() }
            .getOrNull()?.let { epg = Epg.parse(it) }
    }

    val temConteudo: Boolean get() = channels.isNotEmpty()
}
