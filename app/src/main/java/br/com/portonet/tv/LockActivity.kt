package br.com.portonet.tv

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil3.load
import kotlinx.coroutines.launch

/**
 * Tela única de bloqueio. Toda abertura do app passa por aqui primeiro:
 * sincroniza o UUID deste aparelho com a API da Portonet e só deixa
 * prosseguir para o [PlayerActivity] quando o servidor confirma autorização.
 *
 * Sem fluxo de pareamento: o UUID que aparece na tela já é o suficiente
 * para o suporte da Portonet copiar e liberar no painel/banco do lado do
 * servidor (ver seção 3.1 do documento de arquitetura do projeto).
 */
class LockActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var tentando = false

    private companion object {
        const val RETENTATIVA_MS = 5_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)

        findViewById<android.widget.TextView>(R.id.uuid).text = DeviceId.get(this)
        tentarSincronizar()
        EventosSse.observar(lifecycleScope) { tentarSincronizar() }
    }

    /**
     * Sempre tenta a rede primeiro — é assim que uma mudança no painel
     * (canal novo, EPG associado, aparelho revogado) aparece já no próximo
     * boot, em vez de só depois de até 30 min. O cache local só entra como
     * plano B, se a rede falhar de verdade (sem isso, uma instabilidade
     * momentânea não devia travar a tela de bloqueio à toa — ver seção 3.1
     * do documento de arquitetura).
     */
    private fun tentarSincronizar() {
        if (tentando) return
        tentando = true
        val status = findViewById<android.widget.TextView>(R.id.status)
        status.setText(R.string.bloqueio_verificando)

        lifecycleScope.launch {
            when (val resultado = Provisioning.sincronizar(this@LockActivity)) {
                is Sincronizacao.Autorizado -> {
                    ContentRepository.atualizar(this@LockActivity, resultado)
                    aplicarIdentidadeVisual(resultado)
                    if (ContentRepository.temConteudo) {
                        abrirPlayer()
                        return@launch
                    }
                    // Autorizado, mas playlist/EPG ainda não vieram — tenta de novo.
                    status.setText(R.string.bloqueio_verificando)
                    agendarNovaTentativa()
                }
                is Sincronizacao.NaoAutorizado -> {
                    if (resultado.mensagem != null) {
                        status.text = resultado.mensagem
                    } else {
                        status.setText(R.string.bloqueio_verificando)
                    }
                    agendarNovaTentativa()
                }
                Sincronizacao.Indisponivel -> {
                    // Sem rede/servidor fora do ar — só aqui cai pro cache, pra
                    // não deixar o aparelho preso na tela de bloqueio por uma
                    // instabilidade momentânea.
                    if (usarCacheSeDisponivel()) return@launch
                    status.setText(R.string.bloqueio_erro_rede)
                    agendarNovaTentativa()
                }
            }
            tentando = false
        }
    }

    private fun usarCacheSeDisponivel(): Boolean {
        val cache = Provisioning.cache(this) ?: return false
        ContentRepository.carregarCache(this)
        if (!ContentRepository.temConteudo) return false
        aplicarIdentidadeVisual(cache)
        abrirPlayer()
        return true
    }

    /**
     * Cor de fundo e logo vindos do painel — quando ausentes ou inválidos,
     * mantém o azul-marinho e o logo padrão do app (nunca derruba a tela).
     */
    private fun aplicarIdentidadeVisual(autorizado: Sincronizacao.Autorizado) {
        autorizado.temaCorPrimaria?.let { hex ->
            runCatching { findViewById<android.view.View>(R.id.fundoBloqueio).setBackgroundColor(Color.parseColor(hex)) }
        }
        autorizado.temaLogoUrl?.let { url ->
            runCatching { findViewById<android.widget.ImageView>(R.id.logo).load(url) }
        }
    }

    private fun agendarNovaTentativa() {
        handler.postDelayed({ tentarSincronizar() }, RETENTATIVA_MS)
    }

    private fun abrirPlayer() {
        startActivity(Intent(this, PlayerActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
