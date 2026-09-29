package br.com.portonet.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Grade de programação (canais × horário), parecida com o guia de canais de
 * uma TV a cabo/satélite: linhas = canais, colunas = tempo, com uma linha
 * vertical marcando "agora". Desenhada direto em Canvas (não RecyclerView)
 * pra controlar as duas direções de rolagem (canal e tempo) sem precisar
 * sincronizar múltiplas listas — mais simples de manter num app pra TV Box.
 *
 * v1: sem logo dos canais (só número + nome) e sem navegação por D-pad
 * dentro da grade além de cima/baixo — dá pra evoluir depois se precisar.
 */
class EpgGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var aoSelecionarCanal: ((Int) -> Unit)? = null
    var aoFechar: (() -> Unit)? = null

    private var canais: List<Channel> = emptyList()
    private var epg: Map<String, List<Programme>> = emptyMap()

    private var janelaInicioMs = 0L
    private var janelaFimMs = 0L
    private var scrollX = 0f
    private var scrollY = 0f
    var canalFoco = 0
        private set

    private val density = context.resources.displayMetrics.density
    private val alturaLinha = 56 * density
    private val alturaCabecalho = 36 * density
    private val larguraGuia = 168 * density
    private val pxPorMinuto = 4.2f * density

    private val relogio = SimpleDateFormat("HH:mm", Locale("pt", "BR"))

    private var corFundo = Color.parseColor("#081B5C")
    private var corFundoCabecalho = Color.parseColor("#050F38")
    private val corBloco = Color.parseColor("#12308C")
    private var corBlocoAgora = Color.parseColor("#0857FF")
    private val corBlocoFoco = Color.parseColor("#01237C")
    private var corLinhaAgora = Color.parseColor("#FE506C")
    private val corTextoPrincipal = Color.WHITE
    private val corTextoSecundario = Color.parseColor("#B9C3E6")
    private val corDivisor = Color.parseColor("#1E2E7A")

    private val paintFundo = Paint().apply { style = Paint.Style.FILL }
    private val paintBloco = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val paintDivisor = Paint().apply { color = corDivisor; strokeWidth = 1f }
    private val paintLinhaAgora = Paint().apply { color = corLinhaAgora; strokeWidth = 3 * density }
    private val paintTexto = Paint().apply {
        color = corTextoPrincipal; textSize = 14 * density; isAntiAlias = true
    }
    private val paintTextoSecundario = Paint().apply {
        color = corTextoSecundario; textSize = 12 * density; isAntiAlias = true
    }
    private val paintTextoCabecalho = Paint().apply {
        color = corTextoSecundario; textSize = 13 * density; isAntiAlias = true; textAlign = Paint.Align.CENTER
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val maxX = max(0f, larguraConteudo() - (width - larguraGuia))
            val maxY = max(0f, alturaConteudo() - (height - alturaCabecalho))
            scrollX = (scrollX + distanceX).coerceIn(0f, maxX)
            scrollY = (scrollY + distanceY).coerceIn(0f, maxY)
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (e.y < alturaCabecalho) return true // toque no cabeçalho de horário — ignora
            val indice = ((scrollY + e.y - alturaCabecalho) / alturaLinha).toInt()
            if (indice in canais.indices) {
                canalFoco = indice
                aoSelecionarCanal?.invoke(indice)
            }
            return true
        }
    })

    /**
     * Aplica cores vindas do painel (identidade visual) — quando algum dos
     * hex é inválido/nulo, mantém o que já estava (nunca derruba a tela).
     */
    fun aplicarTema(corPrimaria: String?, corDestaque: String?) {
        if (!corPrimaria.isNullOrBlank()) {
            runCatching {
                corFundo = Color.parseColor(corPrimaria)
                corFundoCabecalho = escurecer(corFundo)
            }
        }
        if (!corDestaque.isNullOrBlank()) {
            runCatching {
                val cor = Color.parseColor(corDestaque)
                corBlocoAgora = cor
                corLinhaAgora = cor
            }
        }
        invalidate()
    }

    private fun escurecer(cor: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(cor, hsv)
        hsv[2] *= 0.6f
        return Color.HSVToColor(hsv)
    }

    fun definir(canais: List<Channel>, epg: Map<String, List<Programme>>, canalAtual: Int) {
        this.canais = canais
        this.epg = epg
        this.canalFoco = canalAtual.coerceIn(0, (canais.size - 1).coerceAtLeast(0))
        val agora = System.currentTimeMillis()
        janelaInicioMs = agora - 30 * 60_000L
        janelaFimMs = agora + 6 * 60 * 60_000L
        scrollX = 0f
        scrollY = (canalFoco * alturaLinha - alturaLinha * 2).coerceAtLeast(0f)
        invalidate()
    }

    private fun larguraConteudo(): Float = (janelaFimMs - janelaInicioMs) / 60_000f * pxPorMinuto
    private fun alturaConteudo(): Float = canais.size * alturaLinha

    fun moverFoco(delta: Int) {
        if (canais.isEmpty()) return
        canalFoco = (canalFoco + delta).coerceIn(0, canais.size - 1)
        val topoLinha = canalFoco * alturaLinha
        val areaVisivel = (height - alturaCabecalho).coerceAtLeast(alturaLinha)
        if (topoLinha < scrollY) scrollY = topoLinha
        if (topoLinha + alturaLinha > scrollY + areaVisivel) scrollY = topoLinha + alturaLinha - areaVisivel
        scrollY = scrollY.coerceIn(0f, max(0f, alturaConteudo() - areaVisivel))
        invalidate()
    }

    fun confirmarFoco() {
        if (canalFoco in canais.indices) aoSelecionarCanal?.invoke(canalFoco)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (canais.isEmpty()) return
        val agora = System.currentTimeMillis()

        paintFundo.color = corFundo
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintFundo)

        val areaGradeTopo = alturaCabecalho
        val areaGradeAltura = (height - alturaCabecalho).coerceAtLeast(0f)
        val primeiraLinha = (scrollY / alturaLinha).toInt().coerceAtLeast(0)
        val ultimaLinha = ((scrollY + areaGradeAltura) / alturaLinha).toInt().coerceAtMost(canais.size - 1)

        // --- Blocos de programação -----------------------------------------
        canvas.save()
        canvas.clipRect(larguraGuia, areaGradeTopo, width.toFloat(), height.toFloat())
        for (i in primeiraLinha..ultimaLinha) {
            val canal = canais[i]
            val y = areaGradeTopo + i * alturaLinha - scrollY
            val programas = canal.tvgId?.let { epg[it] } ?: emptyList()
            for (prog in programas) {
                if (prog.stop < janelaInicioMs || prog.start > janelaFimMs) continue
                val inicioMin = (max(prog.start, janelaInicioMs) - janelaInicioMs) / 60_000f
                val fimMin = (min(prog.stop, janelaFimMs) - janelaInicioMs) / 60_000f
                val left = larguraGuia + inicioMin * pxPorMinuto - scrollX
                val right = larguraGuia + fimMin * pxPorMinuto - scrollX
                if (right < larguraGuia || left > width) continue

                paintBloco.color = when {
                    i == canalFoco -> corBlocoFoco
                    prog.isOnAir(agora) -> corBlocoAgora
                    else -> corBloco
                }
                val rect = RectF(max(left, larguraGuia) + 1, y + 3, right - 1, y + alturaLinha - 3)
                canvas.drawRoundRect(rect, 6f, 6f, paintBloco)

                if (rect.width() > 24 * density) {
                    canvas.save()
                    canvas.clipRect(rect)
                    canvas.drawText(prog.title, rect.left + 8 * density, rect.top + alturaLinha / 2 - 4 * density, paintTexto)
                    canvas.restore()
                }
            }
            canvas.drawLine(larguraGuia, y + alturaLinha, width.toFloat(), y + alturaLinha, paintDivisor)
        }
        canvas.restore()

        // --- Linha do "agora" ------------------------------------------------
        if (agora in janelaInicioMs..janelaFimMs) {
            val x = larguraGuia + (agora - janelaInicioMs) / 60_000f * pxPorMinuto - scrollX
            if (x in larguraGuia..width.toFloat()) {
                canvas.drawLine(x, areaGradeTopo, x, height.toFloat(), paintLinhaAgora)
            }
        }

        // --- Cabeçalho de horário (fixo em Y, rola em X) --------------------
        canvas.drawRect(larguraGuia, 0f, width.toFloat(), alturaCabecalho, paintFundo.apply { color = corFundoCabecalho })
        canvas.save()
        canvas.clipRect(larguraGuia, 0f, width.toFloat(), alturaCabecalho)
        var marcaMin = 0f
        val larguraMin = janelaFimMs - janelaInicioMs
        while (marcaMin * 60_000f < larguraMin) {
            val x = larguraGuia + marcaMin * pxPorMinuto - scrollX
            if (x in larguraGuia..width.toFloat()) {
                val instante = janelaInicioMs + (marcaMin * 60_000f).toLong()
                canvas.drawText(relogio.format(instante), x + 30 * density, alturaCabecalho / 2 + 5 * density, paintTextoCabecalho)
                canvas.drawLine(x, 0f, x, height.toFloat(), paintDivisor)
            }
            marcaMin += 30f
        }
        canvas.restore()

        // --- Coluna de canais (fixa em X, rola em Y) ------------------------
        canvas.drawRect(0f, 0f, larguraGuia, height.toFloat(), paintFundo.apply { color = corFundoCabecalho })
        canvas.save()
        canvas.clipRect(0f, areaGradeTopo, larguraGuia, height.toFloat())
        for (i in primeiraLinha..ultimaLinha) {
            val canal = canais[i]
            val y = areaGradeTopo + i * alturaLinha - scrollY
            if (i == canalFoco) {
                canvas.drawRect(0f, y, larguraGuia, y + alturaLinha, paintFundo.apply { color = corBlocoFoco })
            }
            canvas.drawText(
                "${canal.number.toString().padStart(2, '0')}",
                12 * density, y + alturaLinha / 2 - 2 * density, paintTextoSecundario
            )
            canvas.save()
            canvas.clipRect(44 * density, y, larguraGuia - 4 * density, y + alturaLinha)
            canvas.drawText(canal.name, 44 * density, y + alturaLinha / 2 - 2 * density, paintTexto)
            canvas.restore()
            canvas.drawLine(0f, y + alturaLinha, larguraGuia, y + alturaLinha, paintDivisor)
        }
        canvas.restore()
        canvas.drawLine(larguraGuia, 0f, larguraGuia, height.toFloat(), paintDivisor)
    }
}
