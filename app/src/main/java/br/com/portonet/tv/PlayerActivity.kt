package br.com.portonet.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A tela inteira do app: player em tela cheia + zapping por controle,
 * "sensação de canal de TV digital" — sem menu, sem VOD, sem fricção.
 *
 * Controles:
 *  - CIMA/BAIXO ou CANAL+/CANAL-: troca de canal direto.
 *  - ESQUERDA: abre a lista de canais; DIREITA/OK dentro dela: sintoniza.
 *  - 0-9: monta o número do canal, confirma sozinho após um instante.
 *  - CENTRO/OK (fora da lista), um clique: mostra as informações do canal
 *    (nome, logo, programa atual/a seguir). Dois cliques seguidos: abre a
 *    lista de canais na lateral.
 *  - VOLTAR: fecha a lista se estiver aberta; senão não faz nada (não há
 *    "sair" do app — é a própria TV).
 *
 * Em telas de toque (útil pra testar no celular antes de ter um TV Box à
 * mão): deslizar pra cima/baixo troca de canal, um toque no meio mostra as
 * informações do canal, dois toques seguidos abrem a lista de canais — só
 * um atalho de teste, não substitui o controle remoto no TV Box real.
 */
@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var banner: View
    private lateinit var listaPainel: View
    private lateinit var listaCanais: RecyclerView
    private lateinit var numpad: TextView
    private lateinit var manutencao: View

    private val handler = Handler(Looper.getMainLooper())
    private val relogio = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
    private val adapter = ChannelAdapter(this, onPick = { indice -> sintonizar(indice, fecharLista = true) })

    private var atual = 0
    private var fonteIndice = 0
    private var retentativas = 0
    private var listaFoco = 0
    private var numpadTexto = ""

    private val esconderBanner = Runnable { banner.visibility = View.GONE }
    private val confirmarNumpad = Runnable { confirmarNumero() }
    private val mostrarBannerAdiado = Runnable { mostrarBanner() }
    private var ultimoOkMs = 0L

    private var intervaloSincMs = INTERVALO_SINC_PADRAO_MS

    private val tickSincroniza = object : Runnable {
        override fun run() {
            sincronizarPeriodicamente()
            handler.postDelayed(this, intervaloSincMs)
        }
    }

    private lateinit var gestureDetector: GestureDetector

    private companion object {
        const val SOURCE_TIMEOUT_MS = 12_000L
        const val RETENTATIVAS_MAX = 3
        const val BANNER_MS = 4_000L
        const val NUMPAD_MS = 2_000L
        const val INTERVALO_SINC_PADRAO_MS = 30 * 60 * 1000L
        // A primeira ressincronização em segundo plano é rápida — uma mudança
        // no painel (canal, EPG, bloqueio) não deveria esperar até 30 min pra
        // aparecer num app já aberto. Da segunda em diante usa o intervalo
        // configurado no painel (sync_interval_seconds).
        const val PRIMEIRA_SINC_MS = 60_000L
        const val SWIPE_DISTANCIA_MIN = 60
        const val SWIPE_VELOCIDADE_MIN = 200
        const val DUPLO_CLIQUE_MS = 300L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        banner = findViewById(R.id.banner)
        listaPainel = findViewById(R.id.listaPainel)
        numpad = findViewById(R.id.numpad)
        manutencao = findViewById(R.id.manutencao)

        listaCanais = findViewById(R.id.listaCanais)
        listaCanais.layoutManager = LinearLayoutManager(this)
        listaCanais.adapter = adapter
        configurarFiltroFavoritos()

        if (!ContentRepository.temConteudo) {
            // Não deveria acontecer — o LockActivity só abre esta tela com
            // conteúdo em mãos — mas por segurança volta para lá em vez de
            // mostrar uma tela preta sem canal nenhum.
            voltarParaBloqueio()
            return
        }

        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        player.addListener(playerListener)

        adapter.submit(ContentRepository.channels)
        sintonizar(0, fecharLista = false)

        Provisioning.cache(this)?.let { intervaloSincMs = it.intervaloSincSegundos * 1000L }
        handler.postDelayed(tickSincroniza, PRIMEIRA_SINC_MS)
        EventosSse.observar(lifecycleScope) { sincronizarPeriodicamente() }

        configurarToqueDeTeste()
    }

    // --- Toque (atalho de teste em celular, não substitui o controle remoto) --

    private fun configurarToqueDeTeste() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Um toque no meio da tela mostra as informações do canal —
                // igual apertar OK no controle. Espera um instante pra saber
                // se não é o primeiro toque de um duplo-toque (que abre a
                // lista lateral).
                if (listaPainel.visibility == View.VISIBLE) fecharListaCanais() else mostrarBanner()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Dois toques na tela abrem a lista de canais na lateral.
                if (listaPainel.visibility != View.VISIBLE) abrirListaCanais()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // Segurar o toque favorita o canal atual — equivalente ao
                // segurar OK no controle remoto.
                if (listaPainel.visibility != View.VISIBLE) alternarFavoritoCanalAtual()
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null || listaPainel.visibility == View.VISIBLE) return false
                val deltaY = e2.y - e1.y
                val deltaX = e2.x - e1.x

                if (kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY)) {
                    // Horizontal: deslizar da direita pra esquerda abre a lista de
                    // canais, igual a seta esquerda do controle remoto.
                    if (kotlin.math.abs(deltaX) < SWIPE_DISTANCIA_MIN || kotlin.math.abs(velocityX) < SWIPE_VELOCIDADE_MIN) return false
                    if (deltaX < 0) abrirListaCanais()
                    return true
                }

                if (kotlin.math.abs(deltaY) < SWIPE_DISTANCIA_MIN || kotlin.math.abs(velocityY) < SWIPE_VELOCIDADE_MIN) return false
                if (deltaY < 0) canalAdjacente(-1) else canalAdjacente(1)
                return true
            }
        })

        playerView.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }
    }

    // --- Zapping -------------------------------------------------------

    private fun sintonizar(indice: Int, fecharLista: Boolean) {
        val canais = adapter.channels
        if (canais.isEmpty() || indice !in canais.indices) return
        atual = indice
        fonteIndice = 0
        retentativas = 0
        manutencao.visibility = View.GONE
        abrirFonteAtual()
        mostrarBanner()
        if (fecharLista) fecharListaCanais()
        listaFoco = indice
    }

    private fun abrirFonteAtual() {
        val canal = adapter.channels.getOrNull(atual) ?: return
        val fonte = canal.sources.getOrNull(fonteIndice) ?: return
        handler.removeCallbacks(vigiaFonte)
        player.setMediaSource(Playback.mediaSource(fonte))
        player.prepare()
        player.playWhenReady = true
        handler.postDelayed(vigiaFonte, SOURCE_TIMEOUT_MS)
    }

    private val vigiaFonte = Runnable {
        if (player.playbackState != Player.STATE_READY || !player.isPlaying) proximaFonte()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            retentativas++
            if (retentativas <= RETENTATIVAS_MAX) {
                abrirFonteAtual() // Wi-Fi de TV Box oscila — tenta a mesma fonte antes de trocar.
            } else {
                proximaFonte()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) manutencao.visibility = View.GONE
        }
    }

    private fun proximaFonte() {
        val canal = adapter.channels.getOrNull(atual) ?: return
        if (fonteIndice + 1 < canal.sources.size) {
            fonteIndice++
            retentativas = 0
            abrirFonteAtual()
        } else {
            // Esgotou as fontes desse canal — avisa em vez de deixar a
            // imagem parada na última tentativa.
            manutencao.visibility = View.VISIBLE
        }
    }

    private fun canalAdjacente(delta: Int) {
        val canais = adapter.channels
        if (canais.isEmpty()) return
        val proximo = ((atual + delta) % canais.size + canais.size) % canais.size
        sintonizar(proximo, fecharLista = false)
    }

    // --- Banner ----------------------------------------------------------

    private fun mostrarBanner() {
        val canal = adapter.channels.getOrNull(atual) ?: return
        banner.visibility = View.VISIBLE

        findViewById<android.widget.ImageView>(R.id.bannerLogo).load(canal.logo)
        findViewById<TextView>(R.id.bannerNumero).text = formatarNumeroCanal(canal.number)
        findViewById<TextView>(R.id.bannerNome).text = canal.name

        val categoriaView = findViewById<TextView>(R.id.bannerCategoria)
        if (!canal.group.isNullOrBlank()) {
            categoriaView.text = canal.group
            categoriaView.visibility = View.VISIBLE
        } else {
            categoriaView.visibility = View.GONE
        }

        atualizarFavorito(canal)

        val par = Epg.nowNext(ContentRepository.epg, canal.tvgId, System.currentTimeMillis())
        val programaView = findViewById<TextView>(R.id.bannerPrograma)
        val sinopseView = findViewById<TextView>(R.id.bannerSinopse)
        val horarioView = findViewById<TextView>(R.id.bannerHorario)
        val restanteView = findViewById<TextView>(R.id.bannerRestante)
        val proximoView = findViewById<TextView>(R.id.bannerProximo)
        val progresso = findViewById<ProgressBar>(R.id.bannerProgresso)

        if (par != null) {
            val (agora, proximo) = par
            val agoraMs = System.currentTimeMillis()
            programaView.text = agora.title
            sinopseView.text = agora.description
            sinopseView.visibility = if (agora.description.isNullOrBlank()) View.GONE else View.VISIBLE
            horarioView.text = "${relogio.format(agora.start)} – ${relogio.format(agora.stop)}"
            restanteView.text = formatarDuracao(agora.stop - agoraMs)
            progresso.visibility = View.VISIBLE
            progresso.progress = (agora.progress(agoraMs) * 1000).toInt()
            proximoView.text = proximo?.let { "A seguir: ${relogio.format(it.start)} ${it.title}" } ?: ""
            proximoView.visibility = if (proximo != null) View.VISIBLE else View.GONE
        } else {
            programaView.text = canal.name
            sinopseView.visibility = View.GONE
            horarioView.text = ""
            restanteView.text = ""
            progresso.visibility = View.GONE
            proximoView.visibility = View.GONE
        }

        handler.removeCallbacks(esconderBanner)
        handler.postDelayed(esconderBanner, BANNER_MS)
    }

    /** "1h12min" / "45min" — tempo restante do programa atual, pro card do player. */
    private fun formatarDuracao(restanteMs: Long): String {
        val minutosTotais = (restanteMs / 60_000L).coerceAtLeast(0)
        val horas = minutosTotais / 60
        val minutos = minutosTotais % 60
        return if (horas > 0) "${horas}h${minutos.toString().padStart(2, '0')}min" else "${minutos}min"
    }

    private fun atualizarFavorito(canal: Channel) {
        val icone = findViewById<android.widget.ImageView>(R.id.bannerFavorito)
        icone.setImageResource(
            if (FavoritesStore.isFavorito(this, canal)) R.drawable.ic_favorito_on else R.drawable.ic_favorito_off
        )
        icone.setOnClickListener { alternarFavoritoCanalAtual() }
    }

    private fun alternarFavoritoCanalAtual() {
        val canal = adapter.channels.getOrNull(atual) ?: return
        FavoritesStore.alternar(this, canal)
        atualizarFavorito(canal)
        adapter.refresh() // repinta o coraçãozinho na lista lateral, se estiver montada.
        // Mantém o card visível mais um pouco pra dar feedback visual da troca.
        handler.removeCallbacks(esconderBanner)
        handler.postDelayed(esconderBanner, BANNER_MS)
    }

    // --- Lista lateral -----------------------------------------------------

    private fun abrirListaCanais() {
        listaPainel.visibility = View.VISIBLE
        // Com o filtro "somente favoritos" ligado, a posição na tela pode não
        // ser a mesma do índice na lista mestra — e o canal atual pode nem
        // aparecer (não é favorito). Nesse caso só não rola pra lugar nenhum.
        val posicao = adapter.posicaoExibidaDoIndice(listaFoco)
        if (posicao >= 0) listaCanais.scrollToPosition(posicao)
        handler.removeCallbacks(esconderBanner)
        banner.visibility = View.GONE
    }

    private fun fecharListaCanais() {
        listaPainel.visibility = View.GONE
    }

    private fun configurarFiltroFavoritos() {
        val linha = findViewById<View>(R.id.filtroFavoritos)
        atualizarFiltroFavoritosUi()
        linha.setOnClickListener {
            adapter.alternarSomenteFavoritos()
            atualizarFiltroFavoritosUi()
            val posicao = adapter.posicaoExibidaDoIndice(listaFoco)
            if (posicao >= 0) listaCanais.scrollToPosition(posicao)
        }
    }

    private fun atualizarFiltroFavoritosUi() {
        val ligado = adapter.exibindoSomenteFavoritos
        findViewById<android.widget.ImageView>(R.id.filtroFavoritosIcone).setImageResource(
            if (ligado) R.drawable.ic_favorito_on else R.drawable.ic_favorito_off
        )
        findViewById<TextView>(R.id.filtroFavoritosTexto).text =
            getString(if (ligado) R.string.mostrar_todos_canais else R.string.mostrar_somente_favoritos)
    }

    // --- Numpad --------------------------------------------------------

    private fun digitarNumero(digito: Char) {
        numpadTexto = (numpadTexto + digito).takeLast(3)
        numpad.text = numpadTexto
        numpad.visibility = View.VISIBLE
        handler.removeCallbacks(confirmarNumpad)
        handler.postDelayed(confirmarNumpad, NUMPAD_MS)
    }

    private fun confirmarNumero() {
        val digitado = numpadTexto
        numpadTexto = ""
        numpad.visibility = View.GONE
        if (digitado.isBlank()) return
        // O controle remoto só tem dígitos (sem ponto) — "21" precisa achar o
        // canal "2.1" comparando sem o ponto, e "5" continua achando "5" normal.
        val indice = adapter.channels.indexOfFirst { it.number.replace(".", "") == digitado }
        if (indice >= 0) sintonizar(indice, fecharLista = true)
    }

    // --- Sincronização periódica (revogação remota) -----------------------

    private fun sincronizarPeriodicamente() {
        lifecycleScope.launch {
            val canalAtual = adapter.channels.getOrNull(atual)
            when (val resultado = Provisioning.sincronizar(this@PlayerActivity, canalAtual)) {
                is Sincronizacao.Autorizado -> {
                    ContentRepository.atualizar(this@PlayerActivity, resultado)
                    intervaloSincMs = resultado.intervaloSincSegundos * 1000L
                }
                is Sincronizacao.NaoAutorizado -> voltarParaBloqueio()
                Sincronizacao.Indisponivel -> Unit // segue com o que já tem em memória/cache.
            }
        }
    }

    private fun voltarParaBloqueio() {
        player.let { runCatching { it.stop() } }
        startActivity(Intent(this, LockActivity::class.java))
        finish()
    }

    // --- Controle remoto -----------------------------------------------

    /**
     * OK/centro: um clique mostra as informações do canal, dois cliques
     * seguidos (dentro de [DUPLO_CLIQUE_MS]) abrem a lista de canais na
     * lateral — espera um instante antes de mostrar o banner pra saber se
     * não vem um segundo clique.
     */
    private fun tratarCliqueOk() {
        val agora = System.currentTimeMillis()
        if (agora - ultimoOkMs <= DUPLO_CLIQUE_MS) {
            handler.removeCallbacks(mostrarBannerAdiado)
            ultimoOkMs = 0L
            abrirListaCanais()
        } else {
            ultimoOkMs = agora
            handler.removeCallbacks(mostrarBannerAdiado)
            handler.postDelayed(mostrarBannerAdiado, DUPLO_CLIQUE_MS)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            digitarNumero('0' + (keyCode - KeyEvent.KEYCODE_0))
            return true
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_UP -> {
                if (listaPainel.visibility != View.VISIBLE) { canalAdjacente(-1); true } else false
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (listaPainel.visibility != View.VISIBLE) { canalAdjacente(1); true } else false
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (listaPainel.visibility != View.VISIBLE) { abrirListaCanais(); true } else false
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (listaPainel.visibility != View.VISIBLE) { tratarCliqueOk(); true } else false
            }
            KeyEvent.KEYCODE_BACK -> {
                if (listaPainel.visibility == View.VISIBLE) { fecharListaCanais(); true } else false
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /**
     * OK/centro segurado: favorita/desfavorita o canal atual — sem precisar
     * de controle com touchpad. O clique único (onKeyDown, acima) já agenda
     * a exibição do card de informações; se o usuário mantiver pressionado
     * além do limiar de long-press do sistema, o card já estará visível (ou
     * prestes a aparecer) e este favorita em cima dele.
     */
    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (listaPainel.visibility != View.VISIBLE) { alternarFavoritoCanalAtual(); true } else false
            }
            else -> super.onKeyLongPress(keyCode, event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()
    }
}
