package br.com.portonet.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil3.load

class ChannelAdapter(
    private val onPick: (Int) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    var channels: List<Channel> = emptyList()
        private set

    fun submit(lista: List<Channel>) {
        channels = lista
        notifyDataSetChanged()
    }

    /** Repinta em cima, sem trocar a lista — usado quando só o "agora" do EPG muda. */
    fun refresh() = notifyDataSetChanged()

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val numero: TextView = view.findViewById(R.id.numero)
        val logo: ImageView = view.findViewById(R.id.logo)
        val nome: TextView = view.findViewById(R.id.nome)
        val programa: TextView = view.findViewById(R.id.programa)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_canal, parent, false)
        return VH(view)
    }

    override fun getItemCount() = channels.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val canal = channels[position]
        holder.numero.text = formatarNumeroCanal(canal.number)
        holder.nome.text = canal.name
        if (canal.logo != null) holder.logo.load(canal.logo) else holder.logo.setImageDrawable(null)

        val agora = Epg.nowNext(ContentRepository.epg, canal.tvgId, System.currentTimeMillis())?.first
        holder.programa.text = agora?.title ?: ""

        holder.itemView.setOnClickListener { onPick(position) }
    }
}
