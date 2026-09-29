package br.com.portonet.tv

import android.content.Context

/**
 * Canais favoritos do aparelho, salvos localmente (por aparelho, não por
 * conta — este app não tem login de usuário). Chave preferencial é o
 * `tvg-id`, que é estável entre atualizações de playlist; quando o canal não
 * tem `tvg-id` (playlist sem essa tag), cai para "número|nome" como
 * identificador — menos robusto a mudanças de número/nome, mas melhor que
 * não suportar favoritos nesses canais.
 */
object FavoritesStore {
    private const val PREFS = "portonet_favoritos"
    private const val CHAVE = "canais"

    private fun chave(canal: Channel): String = canal.tvgId?.takeIf { it.isNotBlank() }
        ?: "${canal.number}|${canal.name}"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isFavorito(context: Context, canal: Channel): Boolean =
        prefs(context).getStringSet(CHAVE, emptySet())?.contains(chave(canal)) == true

    /** Alterna o estado e devolve o novo estado (true = agora é favorito). */
    fun alternar(context: Context, canal: Channel): Boolean {
        val p = prefs(context)
        val atuais = HashSet(p.getStringSet(CHAVE, emptySet()) ?: emptySet())
        val k = chave(canal)
        val novoEstado = if (atuais.remove(k)) {
            false
        } else {
            atuais.add(k)
            true
        }
        p.edit().putStringSet(CHAVE, atuais).apply()
        return novoEstado
    }
}
