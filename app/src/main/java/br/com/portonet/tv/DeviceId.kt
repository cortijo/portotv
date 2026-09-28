package br.com.portonet.tv

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * O identificador do aparelho: um UUID v4 gerado na primeira execução e
 * persistido em disco. Não usa MAC address — em Android moderno ele nem
 * sempre está acessível/estável, então o UUID local é o que a Portonet
 * cadastra e autoriza do lado do servidor.
 *
 * Sobrevive a reaberturas do app; não sobrevive a desinstalação ou reset de
 * fábrica do TV Box (nesse caso nasce um UUID novo, que precisa ser
 * autorizado de novo no painel da Portonet).
 */
object DeviceId {
    private const val ARQUIVO = "device_id.txt"

    @Volatile
    private var cache: String? = null

    fun get(context: Context): String {
        cache?.let { return it }
        val arquivo = File(context.filesDir, ARQUIVO)
        val existente = runCatching { arquivo.readText().trim() }.getOrNull()
        if (!existente.isNullOrBlank()) {
            cache = existente
            return existente
        }
        val novo = UUID.randomUUID().toString()
        runCatching { arquivo.writeText(novo) }
        cache = novo
        return novo
    }
}
