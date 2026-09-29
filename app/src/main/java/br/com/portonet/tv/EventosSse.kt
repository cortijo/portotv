package br.com.portonet.tv

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Cliente de Server-Sent Events pro `/eventos` do backend — permite que uma
 * mudança no painel (canal novo/editado, EPG associado, bloqueio,
 * revogação, autorização) chegue no app na hora, em vez de esperar a
 * próxima sincronização periódica. Unidirecional (servidor -> app): ao
 * receber qualquer linha `data: ...`, só dispara uma nova tentativa de
 * sincronização pelo caminho que já existe em cada tela
 * (LockActivity.tentarSincronizar / PlayerActivity.sincronizarPeriodicamente)
 * — o próprio /sincronizar já é idempotente e filtra pelo device_id, então
 * não há necessidade de interpretar o motivo do evento aqui.
 */
object EventosSse {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // SSE é uma conexão de streaming de vida longa — sem timeout de
            // leitura, senão o OkHttp derruba a conexão sozinho.
            .readTimeout(Duration.ZERO)
            .build()
    }

    private const val RECONEXAO_MS = 5_000L
    private const val BASE_URL = "http://181.233.106.46:9966"

    /**
     * Fica escutando `/eventos` enquanto [escopo] estiver ativo, chamando
     * [aoReceberEvento] a cada linha `data: ...` recebida. Reconecta
     * automaticamente (com um pequeno atraso) em qualquer queda de conexão
     * — instabilidade de rede, reinício do servidor, etc — para sempre,
     * até o escopo (lifecycleScope da Activity) ser cancelado.
     */
    fun observar(escopo: CoroutineScope, aoReceberEvento: () -> Unit) {
        escopo.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching {
                    val request = Request.Builder()
                        .url("$BASE_URL/eventos")
                        .header("User-Agent", "PortonetTV/1.0")
                        .build()
                    val call = client.newCall(request)
                    // execute() é bloqueante, não suspensa — sem isso, cancelar o
                    // escopo (Activity finalizando) não interrompe a leitura presa
                    // no socket, e a conexão fica pendurada indefinidamente
                    // chamando aoReceberEvento() contra uma Activity já destruída.
                    val cancelamento = coroutineContext.job.invokeOnCompletion { call.cancel() }
                    try {
                        call.execute().use { resposta ->
                            if (!resposta.isSuccessful) return@use
                            val fonte = resposta.body?.source() ?: return@use
                            while (isActive) {
                                val linha = fonte.readUtf8Line() ?: break
                                if (linha.isBlank() || linha.startsWith(":")) continue
                                if (linha.startsWith("data:")) aoReceberEvento()
                            }
                        }
                    } finally {
                        cancelamento.dispose()
                    }
                }.onFailure { Log.w("EventosSse", "conexão SSE caiu, reconectando em ${RECONEXAO_MS}ms", it) }

                if (isActive) delay(RECONEXAO_MS)
            }
        }
    }
}
