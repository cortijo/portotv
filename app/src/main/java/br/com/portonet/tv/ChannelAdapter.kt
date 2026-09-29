package br.com.portonet.tv

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil3.load

/**
 * [context] só é usado pra consultar [FavoritesStore] ao montar a lista
 * filtrada (o filtro "somente favoritos") — o resto do app já lê favoritos
 * a partir do `itemView.context` em cada item, sem precisar disso.
 */
class ChannelAdapter(
    private val context: Context,
    private val onPick: (Int) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    /**
     * Lista mestra, na ordem/numeração cadastrada no painel — é o que o
     * resto do [PlayerActivity] usa pra zapping (CIMA/BAIXO, numpad, canal
     * atual etc.), então nunca é filtrada.
     */
    var channels: List<Channel> = emptyList()
        private set

    /** O que a lista lateral está realmente mostrando (mestra ou só favoritos). */
    private var exibidos: List<Channel> = emptyList()

    /** exibidos[posição na tela] -> índice correspondente em [channels]. */
    private var indicesOriginais: List<Int> = emptyList()

    private var somenteFavoritos = false
    val exibindoSomenteFavoritos: Boolean get() = somenteFavoritos

    fun submit(lista: List<Channel>) {
        channels = lista
        recalcularExibidos()
        notifyDataSetChanged()
    }

    /**
     * Repinta em cima, sem trocar a lista mestra — usado quando só o "agora"
     * do EPG muda, ou quando um favorito é alternado (o filtro pode remover
     * ou trazer de volta uma linha).
     */
    fun refresh() {
        recalcularExibidos()
        notifyDataSetChanged()
    }

    /** Liga/desliga o filtro "somente favoritos" e devolve o novo estado. */
    fun alternarSomenteFavoritos(): Boolean {
        somenteFavoritos = !somenteFavoritos
        recalcularExibidos()
        notifyDataSetChanged()
        return somenteFavoritos
    }

    /**
     * Posição, na lista exibida agora, do canal cujo índice na lista mestra é
     * [indiceOriginal] — usado pra manter o foco/scroll no canal atual ao
     * abrir a lista. -1 se esse canal não aparece com o filtro atual (ex.:
     * canal sintonizado não é favorito, com "somente favoritos" ligado).
     */
    fun posicaoExibidaDoIndice(indiceOriginal: Int): Int = indicesOriginais.indexOf(indiceOriginal)

    private fun recalcularExibidos() {
        if (!somenteFavoritos) {
            exibidos = channels
            indicesOriginais = channels.indices.toList()
            return
        }
        val favoritos = channels.withIndex().filter { (_, canal) -> FavoritesStore.isFavorito(context, canal) }
        exibidos = favoritos.map { it.value }
        indicesOriginais = favoritos.map { it.index }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val numero: TextView = view.findViewById(R.id.numero)
        val logo: ImageView = view.findViewById(R.id.logo)
        val nome: TextView = view.findViewById(R.id.nome)
        val programa: TextView = view.findViewById(R.id.programa)
        val favorito: ImageView = view.findViewById(R.id.favorito)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_canal, parent, false)
        return VH(view)
    }

    override fun getItemCount() = exibidos.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val canal = exibidos[position]
        holder.numero.text = formatarNumeroCanal(canal.number)
        holder.nome.text = canal.name
        if (canal.logo != null) holder.logo.load(canal.logo) else holder.logo.setImageDrawable(null)

        val agora = Epg.nowNext(ContentRepository.epg, canal.tvgId, System.currentTimeMillis())?.first
        holder.programa.text = agora?.title ?: ""

        holder.favorito.visibility =
            if (FavoritesStore.isFavorito(holder.itemView.context, canal)) View.VISIBLE else View.GONE

        val indiceOriginal = indicesOriginais[position]
        holder.itemView.setOnClickListener { onPick(indiceOriginal) }
    }
}
