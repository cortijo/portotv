package br.com.portonet.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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

        // Se já havia uma sincronização autorizada anterior em cache, o app
        // não trava a tela de bloqueio à toa numa instabilidade momentânea
        // de rede — ver seção 3.1 (resiliência) do documento de arquitetura.
        Provisioning.cache(this)?.let {
            ContentRepository.carregarCache(this)
            if (ContentRepository.temConteudo) {
                abrirPlayer()
                return
            }
        }

        tentarSincronizar()
    }

    private fun tentarSincronizar() {
        if (tentando) return
        tentando = true
        val status = findViewById<android.widget.TextView>(R.id.status)
        status.setText(R.string.bloqueio_verificando)

        lifecycleScope.launch {
            when (val resultado = Provisioning.sincronizar(this@LockActivity)) {
                is Sincronizacao.Autorizado -> {
                    ContentRepository.atualizar(this@LockActivity, resultado)
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
                    status.setText(R.string.bloqueio_erro_rede)
                    agendarNovaTentativa()
                }
            }
            tentando = false
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
