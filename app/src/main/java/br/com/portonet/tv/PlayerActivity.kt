package br.com.portonet.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
 *  - CENTRO/OK (fora da lista): abre o guia de programação (grade canais ×
 *    horário); CIMA/BAIXO navega, OK sintoniza.
 *  - VOLTAR: fecha o que estiver aberto (lista ou guia); senão não faz nada
 *    (não há "sair" do app — é a própria TV).
 *
 * Em telas de toque (útil pra testar no celular antes de ter um TV Box à
 * mão): deslizar pra cima/baixo troca de canal, deslizar da direita pra
 * esquerda abre a lista de canais, e tocar no meio abre o guia de
 * programação — só um atalho de teste, não substitui o controle remoto no
 * TV Box real.
 */
@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var banner: View
    private lateinit var listaPainel: View
    private lateinit var listaCanais: RecyclerView
    private lateinit var numpad: TextView
    private lateinit var epgGrid: EpgGridView

    private val handler = Handler(Looper.getMainLooper())
    private val relogio = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
    private val adapter = ChannelAdapter(onPick = { indice -> sintonizar(indice, fecharLista = true) })

    private var atual = 0
    private var fonteIndice = 0
    private var retentativas = 0
    private var listaFoco = 0
    private var numpadTexto = ""

    private val esconderBanner = Runnable { banner.visibility = View.GONE }
    private val confirmarNumpad = Runnable { confirmarNumero() }

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
        const val PIP_LARGURA_DP = 320
        const val PIP_ALTURA_DP = 180
        const val PIP_MARGEM_DP = 24
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        banner = findViewById(R.id.banner)
        listaPainel = findViewById(R.id.listaPainel)
        numpad = findViewById(R.id.numpad)
        epgGrid = findViewById(R.id.epgGrid)
        epgGrid.aoSelecionarCanal = { indice -> sintonizar(indice, fecharLista = false); fecharEpgGrid() }

        listaCanais = findViewById(R.id.listaCanais)
        listaCanais.layoutManager = LinearLayoutManager(this)
        listaCanais.adapter = adapter

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

        Provisioning.cache(this)?.let {
            intervaloSincMs = it.intervaloSincSegundos * 1000L
            runCatching { epgGrid.aplicarTema(it.temaCorPrimaria, it.temaCorDestaque) }
        }
        handler.postDelayed(tickSincroniza, PRIMEIRA_SINC_MS)

        configurarToqueDeTeste()
    }

    // --- Toque (atalho de teste em celular, não substitui o controle remoto) --

    private fun configurarToqueDeTeste() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // Tocar no meio da tela abre o guia de programação — igual
                // apertar "guia"/OK no controle.
                when {
                    epgGrid.visibility == View.VISIBLE -> Unit // a própria grade trata o toque
                    listaPainel.visibility == View.VISIBLE -> fecharListaCanais()
                    else -> abrirEpgGrid()
                }
                return true
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
    }

    private fun proximaFonte() {
        val canal = adapter.channels.getOrNull(atual) ?: return
        if (fonteIndice + 1 < canal.sources.size) {
            fonteIndice++
            retentativas = 0
            abrirFonteAtual()
        }
        // Sem próxima fonte: fica no que tem — trocar de canal sozinho seria
        // pior do que deixar a imagem parada na última tentativa.
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
        findViewById<TextView>(R.id.bannerNumeroNome).text =
            "${canal.number.toString().padStart(2, '0')} · ${canal.name}"

        val par = Epg.nowNext(ContentRepository.epg, canal.tvgId, System.currentTimeMillis())
        val agoraView = findViewById<TextView>(R.id.bannerAgora)
        val proximoView = findViewById<TextView>(R.id.bannerProximo)
        val progresso = findViewById<ProgressBar>(R.id.bannerProgresso)

        if (par != null) {
            val (agora, proximo) = par
            agoraView.text = "${relogio.format(agora.start)} ${agora.title}"
            progresso.visibility = View.VISIBLE
            progresso.progress = (agora.progress(System.currentTimeMillis()) * 1000).toInt()
            proximoView.text = proximo?.let { "A seguir: ${relogio.format(it.start)} ${it.title}" } ?: ""
        } else {
            agoraView.text = ""
            progresso.visibility = View.GONE
            proximoView.text = ""
        }

        handler.removeCallbacks(esconderBanner)
        handler.postDelayed(esconderBanner, BANNER_MS)
    }

    // --- Lista lateral -----------------------------------------------------

    private fun abrirListaCanais() {
        listaPainel.visibility = View.VISIBLE
        listaCanais.scrollToPosition(listaFoco)
        handler.removeCallbacks(esconderBanner)
        banner.visibility = View.GONE
    }

    private fun fecharListaCanais() {
        listaPainel.visibility = View.GONE
    }

    // --- Guia de programação (grade canais × horário) ----------------------

    private fun abrirEpgGrid() {
        fecharListaCanais()
        handler.removeCallbacks(esconderBanner)
        banner.visibility = View.GONE
        epgGrid.definir(adapter.channels, ContentRepository.epg, atual)
        epgGrid.visibility = View.VISIBLE
        encolherPlayerEmPip()
    }

    private fun fecharEpgGrid() {
        epgGrid.visibility = View.GONE
        restaurarPlayerEmTelaCheia()
    }

    /**
     * Encolhe o vídeo num "canto" (estilo PiP) enquanto o guia está aberto,
     * sem soltar/recriar o player — só reposiciona a PlayerView, que segue
     * tocando normalmente por baixo da grade. A grade fica atrás, o vídeo
     * flutua por cima (elevação), então não precisa recalcular o desenho
     * do EpgGridView.
     */
    private fun encolherPlayerEmPip() {
        val densidade = resources.displayMetrics.density
        val largura = (PIP_LARGURA_DP * densidade).toInt()
        val altura = (PIP_ALTURA_DP * densidade).toInt()
        val margem = (PIP_MARGEM_DP * densidade).toInt()
        playerView.layoutParams = (playerView.layoutParams as FrameLayout.LayoutParams).apply {
            width = largura
            height = altura
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setMargins(margem, margem, 0, 0)
        }
        playerView.elevation = 24f
        playerView.bringToFront()
    }

    private fun restaurarPlayerEmTelaCheia() {
        playerView.layoutParams = (playerView.layoutParams as FrameLayout.LayoutParams).apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
            gravity = android.view.Gravity.NO_GRAVITY
            setMargins(0, 0, 0, 0)
        }
        playerView.elevation = 0f
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
        val numero = numpadTexto.toIntOrNull()
        numpadTexto = ""
        numpad.visibility = View.GONE
        if (numero == null) return
        val indice = adapter.channels.indexOfFirst { it.number == numero }
        if (indice >= 0) sintonizar(indice, fecharLista = true)
    }

    // --- Sincronização periódica (revogação remota) -----------------------

    private fun sincronizarPeriodicamente() {
        lifecycleScope.launch {
            when (val resultado = Provisioning.sincronizar(this@PlayerActivity)) {
                is Sincronizacao.Autorizado -> {
                    ContentRepository.atualizar(this@PlayerActivity, resultado)
                    intervaloSincMs = resultado.intervaloSincSegundos * 1000L
                    runCatching { epgGrid.aplicarTema(resultado.temaCorPrimaria, resultado.temaCorDestaque) }
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (epgGrid.visibility == View.VISIBLE) return tratarTeclaNoGuia(keyCode)

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
                if (listaPainel.visibility != View.VISIBLE) { abrirEpgGrid(); true } else false
            }
            KeyEvent.KEYCODE_BACK -> {
                if (listaPainel.visibility == View.VISIBLE) { fecharListaCanais(); true } else false
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun tratarTeclaNoGuia(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { epgGrid.moverFoco(-1); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { epgGrid.moverFoco(1); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { epgGrid.confirmarFoco(); true }
            KeyEvent.KEYCODE_BACK -> { fecharEpgGrid(); true }
            else -> true // engole o resto enquanto o guia está aberto (não deixa trocar de canal por baixo)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()
    }
}
