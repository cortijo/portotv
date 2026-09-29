package br.com.portonet.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Resultado de uma sincronização com a API da Portonet — ver o contrato
 * proposto na seção 7 do documento "análise SaimoTV e proposta Portonet"
 * do projeto. Endpoint e formato ainda não são definitivos: este objeto é
 * o ponto único a ajustar quando o backend real existir.
 */
sealed class Sincronizacao {
    data class Autorizado(
        val playlistUrl: String,
        val epgUrl: String,
        val playlistTtlSegundos: Long,
        val epgTtlSegundos: Long,
        val intervaloSincSegundos: Long,
        /** Identidade visual vinda do painel — nulo quando não configurada, mantém o padrão do app. */
        val temaCorPrimaria: String? = null,
        val temaCorDestaque: String? = null,
        val temaLogoUrl: String? = null,
    ) : Sincronizacao()

    /** [mensagem]: motivo do bloqueio, quando o suporte define um ao bloquear o aparelho no painel. */
    data class NaoAutorizado(val mensagem: String? = null) : Sincronizacao()
    /** API fora do ar / resposta ilegível — quem chama decide se usa o cache. */
    object Indisponivel : Sincronizacao()
}

/**
 * Sincroniza o UUID deste aparelho com o servidor da Portonet. Chamado no
 * boot (LockActivity) e periodicamente em segundo plano (PlayerActivity)
 * para que uma revogação do lado do servidor derrube o app para a tela de
 * bloqueio sem precisar reiniciar o TV Box.
 *
 * A URL base aponta para a API de provisionamento em `backend/` — trocar
 * aqui não exige mudar mais nada no app.
 */
object Provisioning {

    private const val BASE_URL = "http://181.233.106.46:9966"
    private const val PADRAO_INTERVALO_SEGUNDOS = 30 * 60L
    private const val PADRAO_TTL_SEGUNDOS = 6 * 60 * 60L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    suspend fun sincronizar(context: Context): Sincronizacao = withContext(Dispatchers.IO) {
        val deviceId = DeviceId.get(context)
        val rede = tipoDeRede(context)
        val sinal = if (rede == "wifi") sinalWifi(context) else null
        val parametrosSinal = sinal?.let { "&sinal_dbm=${it.dbm}&sinal_nivel=${it.nivel}" } ?: ""
        val cpuPct = cpuPercentual()
        val parametrosCpu = cpuPct?.let { "&cpu_pct=$it" } ?: ""
        val corpo = runCatching {
            val request = Request.Builder()
                .url("$BASE_URL/sincronizar?device_id=$deviceId&rede=$rede$parametrosSinal$parametrosCpu")
                .header("User-Agent", "PortonetTV/1.0")
                .build()
            client.newCall(request).execute().use { resposta ->
                if (!resposta.isSuccessful) null else resposta.body?.string()
            }
        }.getOrNull() ?: return@withContext Sincronizacao.Indisponivel

        runCatching {
            val json = JSONObject(corpo)
            when (json.optString("status")) {
                "autorizado" -> {
                    val resultado = Sincronizacao.Autorizado(
                        playlistUrl = json.getString("playlist_url"),
                        epgUrl = json.getString("epg_url"),
                        playlistTtlSegundos = json.optLong("playlist_ttl_seconds", PADRAO_TTL_SEGUNDOS),
                        epgTtlSegundos = json.optLong("epg_ttl_seconds", PADRAO_TTL_SEGUNDOS),
                        intervaloSincSegundos = json.optLong("sync_interval_seconds", PADRAO_INTERVALO_SEGUNDOS),
                        temaCorPrimaria = json.optString("tema_cor_primaria").ifBlank { null },
                        temaCorDestaque = json.optString("tema_cor_destaque").ifBlank { null },
                        temaLogoUrl = json.optString("tema_logo_url").ifBlank { null },
                    )
                    salvarCache(context, resultado)
                    resultado
                }
                "nao_autorizado" -> Sincronizacao.NaoAutorizado(json.optString("mensagem").ifBlank { null })
                else -> Sincronizacao.Indisponivel
            }
        }.getOrElse { Sincronizacao.Indisponivel }
    }

    /** Tipo de rede atual — só pra o painel de suporte enxergar como o aparelho está conectado. */
    private fun tipoDeRede(context: Context): String = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capacidades = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "desconhecida"
        when {
            capacidades.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capacidades.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capacidades.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "celular"
            else -> "desconhecida"
        }
    }.getOrDefault("desconhecida")

    private data class SinalWifi(val dbm: Int, val nivel: Int)

    /** Força do sinal Wi-Fi atual: dBm bruto + nível 0-4, só quando conectado por Wi-Fi. */
    @Suppress("DEPRECATION")
    private fun sinalWifi(context: Context): SinalWifi? = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val rssi = wm.connectionInfo?.rssi ?: return null
        if (rssi == 0 || rssi == Int.MIN_VALUE) return null // sem leitura válida
        SinalWifi(dbm = rssi, nivel = WifiManager.calculateSignalLevel(rssi, 5))
    }.getOrNull()

    /**
     * Uso de processamento do aparelho, normalizado 0-100 — lido de
     * `/proc/loadavg` (carga média de 1 min, dividida pelo nº de núcleos),
     * sem depender de duas amostragens no tempo. Só informativo, pro
     * suporte perceber um TV Box sobrecarregado; se não der pra ler (ROM
     * restringindo acesso a /proc), simplesmente não envia.
     */
    private fun cpuPercentual(): Int? = runCatching {
        val primeiraColuna = File("/proc/loadavg").readText().trim().substringBefore(' ')
        val cargaMediaUmMin = primeiraColuna.toFloat()
        val nucleos = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        ((cargaMediaUmMin / nucleos) * 100).toInt().coerceIn(0, 100)
    }.getOrNull()

    /** Última sincronização autorizada com sucesso, sem tocar na rede. */
    fun cache(context: Context): Sincronizacao.Autorizado? = runCatching {
        val texto = File(context.filesDir, "provisioning_cache.json").readText()
        val json = JSONObject(texto)
        Sincronizacao.Autorizado(
            playlistUrl = json.getString("playlist_url"),
            epgUrl = json.getString("epg_url"),
            playlistTtlSegundos = json.optLong("playlist_ttl_seconds", PADRAO_TTL_SEGUNDOS),
            epgTtlSegundos = json.optLong("epg_ttl_seconds", PADRAO_TTL_SEGUNDOS),
            intervaloSincSegundos = json.optLong("sync_interval_seconds", PADRAO_INTERVALO_SEGUNDOS),
            // optString tolera cache antigo (gravado antes deste campo existir).
            temaCorPrimaria = json.optString("tema_cor_primaria").ifBlank { null },
            temaCorDestaque = json.optString("tema_cor_destaque").ifBlank { null },
            temaLogoUrl = json.optString("tema_logo_url").ifBlank { null },
        )
    }.getOrNull()

    private fun salvarCache(context: Context, autorizado: Sincronizacao.Autorizado) {
        val json = JSONObject().apply {
            put("playlist_url", autorizado.playlistUrl)
            put("epg_url", autorizado.epgUrl)
            put("playlist_ttl_seconds", autorizado.playlistTtlSegundos)
            put("epg_ttl_seconds", autorizado.epgTtlSegundos)
            put("sync_interval_seconds", autorizado.intervaloSincSegundos)
            autorizado.temaCorPrimaria?.let { put("tema_cor_primaria", it) }
            autorizado.temaCorDestaque?.let { put("tema_cor_destaque", it) }
            autorizado.temaLogoUrl?.let { put("tema_logo_url", it) }
        }
        runCatching { File(context.filesDir, "provisioning_cache.json").writeText(json.toString()) }
    }
}
