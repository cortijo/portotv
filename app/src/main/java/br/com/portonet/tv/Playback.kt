package br.com.portonet.tv

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import okhttp3.OkHttpClient

/**
 * Monta o [MediaSource] certo a partir de uma [Source] da playlist.
 *
 * Extensão `.m3u8` -> HLS, `.mpd` -> DASH, qualquer outra coisa -> stream
 * progressivo (MPEG-TS bruto), que é como metade das listas IPTV serve
 * canal — sem nenhuma extensão reconhecível na URL. Mandar isso para o
 * HlsMediaSource seria pedir uma playlist a quem só entrega vídeo direto, e
 * o canal simplesmente não abre.
 */
@UnstableApi
object Playback {

    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"

    private val client: OkHttpClient by lazy { OkHttpClient.Builder().build() }

    fun mediaSource(source: Source): MediaSource {
        val upstream: DataSource.Factory = OkHttpDataSource.Factory(client)
            .setUserAgent(DEFAULT_USER_AGENT)
        val item = MediaItem.fromUri(source.url)
        return when {
            source.isDash -> DashMediaSource.Factory(upstream).createMediaSource(item)
            source.isHls -> HlsMediaSource.Factory(upstream).setAllowChunklessPreparation(true).createMediaSource(item)
            else -> ProgressiveMediaSource.Factory(upstream).createMediaSource(item)
        }
    }
}
