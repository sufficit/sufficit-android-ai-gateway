package com.sufficit.ai.gateway

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import com.sufficit.ai.gateway.runtime.GatewayRuntime
import com.sufficit.ai.gateway.vision.HandPoint
import com.sufficit.ai.gateway.vision.HandTrackingFrame
import com.sufficit.ai.gateway.vision.TrackedHand
import kotlin.math.max

/**
 * Maos desenhadas sobre a tela replicando os movimentos rastreados pelo
 * MediaPipe. Suporta skins (ver [HandGloveSkin]):
 *  - CARTOON: luva de desenho animado — silhueta lisa, cor chapada, contorno
 *    unico grosso (uniao de tracos: passada de contorno + passada de
 *    preenchimento escondem as emendas internas).
 *  - HOLOGRAM: luva tecnica com profundidade — z dos landmarks vira brilho e
 *    espessura, partes distantes desenhadas primeiro (painter's algorithm).
 */
@Composable
fun HandGloveOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (GatewayRuntime.handSkin().value == null) {
            GatewayRuntime.setHandSkin(HandGloveSkinStore.load(context).id)
        }
    }
    val skinId by GatewayRuntime.handSkin().collectAsState()
    val frame by GatewayRuntime.handTracking().collectAsState()
    val smoother = remember { HandLandmarkSmoother() }
    val raw = frame ?: return
    if (raw.hands.isEmpty()) return
    val skin = HandGloveSkin.fromId(skinId)
    // Cartoon pede movimento mais "gelatinoso"; holograma responde mais rapido.
    val smoothed = smoother.smooth(
        raw,
        factor = if (skin == HandGloveSkin.CARTOON) 0.45f else 0.7f
    )
    Canvas(modifier = modifier) {
        smoothed.hands
            .filter { it.points.size >= LANDMARK_COUNT }
            .sortedByDescending { hand -> hand.points.sumOf { it.z.toDouble() } }
            .forEach { hand ->
                when (skin) {
                    HandGloveSkin.CARTOON -> drawCartoonGlove(hand, smoothed)
                    HandGloveSkin.HOLOGRAM -> drawHologramGlove(hand, smoothed)
                }
            }
    }
}

private const val LANDMARK_COUNT = 21
private const val WRIST = 0
private const val MIDDLE_MCP = 9

// Contorno da palma: pulso -> base do polegar -> bases dos dedos -> pulso.
private val PALM_OUTLINE = intArrayOf(0, 1, 5, 9, 13, 17)

// No cartoon a palma nao inclui a base do polegar: o traco largo da palma
// engolia o primeiro segmento do dedao, que parecia sair de tras da mao.
private val CARTOON_PALM_OUTLINE = intArrayOf(0, 5, 9, 13, 17)

// Tints suaves do punho para indicar a mao (entrada nao espelhada: o
// handedness do MediaPipe chega invertido, swap aplicado aqui).
private val LeftHandTint = Color(0xFF6FA8FF)
private val RightHandTint = Color(0xFF59D9A1)

private fun handTint(handedness: String?): Color? = when (handedness?.lowercase()) {
    "left" -> RightHandTint
    "right" -> LeftHandTint
    else -> null
}

// Cadeias de segmentos de cada dedo (a base do polegar ja participa da palma).
private val FINGER_CHAINS = arrayOf(
    intArrayOf(1, 2, 3, 4),
    intArrayOf(5, 6, 7, 8),
    intArrayOf(9, 10, 11, 12),
    intArrayOf(13, 14, 15, 16),
    intArrayOf(17, 18, 19, 20)
)

// ---------------------------------------------------------------------------
// Skin CARTOON
// ---------------------------------------------------------------------------

private val CartoonFill = Color(0xFFFFFFFF)
private val CartoonShadow = Color(0xFFD4DAE3)
private val CartoonOutline = Color(0xFF46566E)
private val CartoonHalo = Color(0x59FFFFFF)
private const val CARTOON_HAND_SCALE = 0.85f
private const val CARTOON_FINGER_LENGTH = 0.80f

private fun DrawScope.drawCartoonGlove(hand: TrackedHand, frame: HandTrackingFrame) {
    val points = hand.points
    val rawMapped = points.map { mapToCanvas(it, frame) }
    // Encolhe a mao inteira em direcao ao centro: largura de traco nao muda a
    // extensao do esqueleto, so a escala dos proprios pontos muda o tamanho.
    val center = rawMapped[WRIST] + (rawMapped[MIDDLE_MCP] - rawMapped[WRIST]) * 0.5f
    val mapped = rawMapped.map { center + (it - center) * CARTOON_HAND_SCALE }
    // Quadro com landmark degenerado (NaN/Inf) derruba o Path.combine e
    // crashava o app no draw. Quadro ruim = nao desenha esta mao.
    if (mapped.any { !it.x.isFinite() || !it.y.isFinite() }) return
    val handScale = (mapped[WRIST] - mapped[MIDDLE_MCP]).getDistance()
    if (handScale < 1f || !handScale.isFinite()) return
    val baseWidth = (handScale * 0.34f).coerceIn(12f, 64f)
    val fingerWidth = baseWidth * 0.95f
    val palmWidth = baseWidth * 1.55f
    val outlineExtra = (baseWidth * 0.075f).coerceAtLeast(2.5f) * 2f

    // Palma com contorno curvo (sem quinas de poligono).
    val palmPath = smoothClosedPath(CARTOON_PALM_OUTLINE.map { mapped[it] })
    val cuffRadius = baseWidth * 0.85f
    val cuffFill = handTint(hand.handedness)
        ?.let { tint -> androidx.compose.ui.graphics.lerp(CartoonFill, tint, 0.55f) }
        ?: CartoonFill

    // Dedos encurtados (pontos puxados para a base da cadeia).
    val fingers = FINGER_CHAINS.map { chain ->
        val chainPoints = chain.map { mapped[it] }
        val base = chainPoints.first()
        chainPoints.map { base + (it - base) * CARTOON_FINGER_LENGTH }
    }

    // Silhueta REAL da mao: uniao booleana (palma preenchida + area dos
    // tracos da palma e dos dedos convertidos em path de preenchimento).
    // Um unico path fechado permite preenchimento limpo, sombra recortada
    // exata e UM contorno uniforme — sem artefatos de camadas sobrepostas.
    //
    // Path.combine pode FALHAR (IllegalArgumentException) com geometria
    // degenerada de um quadro ruim do rastreamento — derrubava o app no
    // meio do draw. Falhou = pula o desenho desta mao neste quadro.
    val lightShift = Offset(-baseWidth * 0.28f, -baseWidth * 0.28f)
    val silhouette: Path
    val shadowCrescent: Path
    try {
        var union = Path.combine(
            PathOperation.Union,
            palmPath,
            strokeToFillPath(palmPath, palmWidth)
        )
        fingers.forEach { finger ->
            union = Path.combine(
                PathOperation.Union,
                union,
                strokeToFillPath(smoothPathThrough(finger), fingerWidth)
            )
        }
        // Sombra cel-shading: regiao da silhueta que fica descoberta quando
        // a forma e deslocada na direcao da luz (alto-esquerda).
        val shifted = Path().apply { addPath(union, lightShift) }
        silhouette = union
        shadowCrescent = Path.combine(PathOperation.Difference, union, shifted)
    } catch (_: IllegalArgumentException) {
        return
    }

    // Punho ANTES da mao (fica atras, como faixa de luva), com a cor de
    // esquerda/direita e a mesma tecnica de sombra recortada.
    val wristOut = mapped[WRIST] - mapped[MIDDLE_MCP]
    val wristDist = max(wristOut.getDistance(), 1f)
    val cuffCenter = mapped[WRIST] +
        Offset(wristOut.x / wristDist, wristOut.y / wristDist) * (cuffRadius * 0.75f)
    val cuffPath = Path().apply {
        addOval(
            androidx.compose.ui.geometry.Rect(
                left = cuffCenter.x - cuffRadius,
                top = cuffCenter.y - cuffRadius,
                right = cuffCenter.x + cuffRadius,
                bottom = cuffCenter.y + cuffRadius
            )
        )
    }
    val cuffShifted = Path().apply { addPath(cuffPath, lightShift * 0.6f) }
    val cuffCrescent = try {
        Path.combine(PathOperation.Difference, cuffPath, cuffShifted)
    } catch (_: IllegalArgumentException) {
        return
    }
    val cuffShadowColor = handTint(hand.handedness)
        ?.let { tint -> androidx.compose.ui.graphics.lerp(CartoonShadow, tint, 0.55f) }
        ?: CartoonShadow
    // Halo claro por fora (estilo sticker): separa a luva de fundos escuros.
    drawPath(
        cuffPath,
        color = CartoonHalo,
        style = Stroke(width = outlineExtra * 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    drawPath(cuffPath, color = cuffFill)
    drawPath(cuffCrescent, color = cuffShadowColor)
    drawPath(
        cuffPath,
        color = CartoonOutline,
        style = Stroke(width = outlineExtra, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    drawPath(
        silhouette,
        color = CartoonHalo,
        style = Stroke(width = outlineExtra * 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    drawPath(silhouette, color = CartoonFill)
    drawPath(shadowCrescent, color = CartoonShadow)
    drawPath(
        silhouette,
        color = CartoonOutline,
        style = Stroke(width = outlineExtra, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // Linhas estruturais bem sutis: dobras de pele e tensao, para leitura
    // do posicionamento sem poluir o desenho.
    val softColor = CartoonOutline.copy(alpha = 0.15f)
    val softWidth = (outlineExtra * 0.35f).coerceAtLeast(1.5f)

    fun jointTick(at: Offset, axisFrom: Offset, axisTo: Offset, halfLength: Float) {
        val axis = axisTo - axisFrom
        val axisLen = max(axis.getDistance(), 1f)
        val perp = Offset(-axis.y / axisLen, axis.x / axisLen)
        drawLine(
            color = softColor,
            start = at - perp * halfLength,
            end = at + perp * halfLength,
            strokeWidth = softWidth,
            cap = StrokeCap.Round
        )
    }

    // Dedos dobrados (punho fechado): linha interna do gancho do dedo
    // enrolado — sem isso o punho vira uma bolha lisa ilegivel.
    // Dedos estendidos: dobra de pele sutil na junta do meio (PIP).
    fingers.forEachIndexed { index, finger ->
        val chain = FINGER_CHAINS[index]
        val tipDist = (mapped[chain.last()] - mapped[WRIST]).getDistance()
        val mcpDist = (mapped[chain.first()] - mapped[WRIST]).getDistance()
        if (tipDist < mcpDist * 1.02f) {
            drawPath(
                smoothPathThrough(finger.drop(1)),
                color = CartoonOutline.copy(alpha = 0.40f),
                style = Stroke(
                    width = (outlineExtra * 0.5f).coerceAtLeast(2f),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        } else {
            jointTick(
                at = finger[1],
                axisFrom = finger[0],
                axisTo = finger[2],
                halfLength = fingerWidth * 0.26f
            )
        }
    }

    // Palma vs costas: orientacao da mao pelo produto vetorial das bases
    // (indicador e mindinho em relacao ao pulso) combinado com o handedness.
    val v1 = mapped[5] - mapped[WRIST]
    val v2 = mapped[17] - mapped[WRIST]
    val crossZ = v1.x * v2.y - v1.y * v2.x
    val reportedLeft = hand.handedness?.lowercase() == "left"
    val showingBack = (crossZ > 0f) == reportedLeft
    val markColor = CartoonOutline.copy(alpha = 0.35f)
    val markWidth = (outlineExtra * 0.35f).coerceAtLeast(2.5f)
    if (showingBack) {
        // Costas: tres costuras classicas de luva, do dorso rumo aos nos.
        intArrayOf(5, 9, 13).forEach { idx ->
            drawLine(
                color = markColor,
                start = mapped[WRIST] + (mapped[idx] - mapped[WRIST]) * 0.48f,
                end = mapped[WRIST] + (mapped[idx] - mapped[WRIST]) * 0.78f,
                strokeWidth = markWidth,
                cap = StrokeCap.Round
            )
        }
        // Nos dos dedos: pele esticada sobre a fileira de juntas (MCP).
        intArrayOf(5, 9, 13, 17).forEach { idx ->
            jointTick(
                at = mapped[idx],
                axisFrom = mapped[WRIST],
                axisTo = mapped[idx + 1],
                halfLength = fingerWidth * 0.30f
            )
        }
    } else {
        // Palma: vinco curvo atravessando a mao (linha da palma).
        val creaseStart = mapped[5] + (mapped[WRIST] - mapped[5]) * 0.32f
        val creaseEnd = mapped[17] + (mapped[WRIST] - mapped[17]) * 0.32f
        val midBase = (creaseStart + creaseEnd) * 0.5f
        val control = midBase + (mapped[WRIST] - midBase) * 0.40f
        val palmCrease = Path().apply {
            moveTo(creaseStart.x, creaseStart.y)
            quadraticBezierTo(control.x, control.y, creaseEnd.x, creaseEnd.y)
        }
        drawPath(
            palmCrease,
            color = markColor,
            style = Stroke(width = markWidth, cap = StrokeCap.Round)
        )
        // Vinco tenar: pele puxada ao redor da base do polegar.
        val thenarStart = (mapped[2] + mapped[5]) * 0.5f
        val thenarEnd = mapped[WRIST] + (mapped[1] - mapped[WRIST]) * 0.35f
        val thenarMid = (thenarStart + thenarEnd) * 0.5f
        val thenarControl = thenarMid + (mapped[9] - thenarMid) * 0.18f
        val thenarPath = Path().apply {
            moveTo(thenarStart.x, thenarStart.y)
            quadraticBezierTo(thenarControl.x, thenarControl.y, thenarEnd.x, thenarEnd.y)
        }
        drawPath(
            thenarPath,
            color = softColor,
            style = Stroke(width = softWidth, cap = StrokeCap.Round)
        )
    }
}

/** Converte um traco (stroke arredondado) na area preenchida equivalente. */
private fun strokeToFillPath(path: Path, width: Float): Path {
    val paint = android.graphics.Paint().apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = width
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    val result = android.graphics.Path()
    paint.getFillPath(path.asAndroidPath(), result)
    return result.asComposePath()
}

/** Curva fechada suave (Catmull-Rom com wrap-around) pelos pontos. */
private fun smoothClosedPath(points: List<Offset>): Path {
    val path = Path()
    val n = points.size
    if (n < 3) return path
    path.moveTo(points[0].x, points[0].y)
    for (i in 0 until n) {
        val p0 = points[(i - 1 + n) % n]
        val p1 = points[i]
        val p2 = points[(i + 1) % n]
        val p3 = points[(i + 2) % n]
        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
    }
    path.close()
    return path
}

/** Curva suave (Catmull-Rom convertida em cubicas) passando pelos pontos. */
private fun smoothPathThrough(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    if (points.size == 2) {
        path.lineTo(points[1].x, points[1].y)
        return path
    }
    for (i in 0 until points.size - 1) {
        val p0 = points[max(i - 1, 0)]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[minOf(i + 2, points.size - 1)]
        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
    }
    return path
}

// ---------------------------------------------------------------------------
// Skin HOLOGRAM (a luva branca original com profundidade)
// ---------------------------------------------------------------------------

private fun DrawScope.drawHologramGlove(hand: TrackedHand, frame: HandTrackingFrame) {
    val points = hand.points
    val mapped = points.map { mapToCanvas(it, frame) }
    val zMin = points.minOf { it.z }
    val zMax = points.maxOf { it.z }
    val zRange = max(zMax - zMin, 1e-4f)

    // 1.0 = mais perto da camera (mais claro), 0.62 = mais longe (mais escuro).
    fun shade(z: Float): Float = 1f - ((z - zMin) / zRange) * 0.38f

    val handScale = (mapped[WRIST] - mapped[MIDDLE_MCP]).getDistance()
    if (handScale < 1f) return
    val baseWidth = (handScale * 0.34f).coerceIn(14f, 64f)

    drawHologramPalm(mapped, points, baseWidth, ::shade)
    drawHologramCuff(mapped[WRIST], points[WRIST].z, baseWidth, hand.handedness, ::shade)

    FINGER_CHAINS
        .sortedByDescending { chain -> chain.sumOf { points[it].z.toDouble() } }
        .forEach { chain -> drawHologramFinger(chain, mapped, points, baseWidth, ::shade) }
}

private fun DrawScope.drawHologramPalm(
    mapped: List<Offset>,
    points: List<HandPoint>,
    baseWidth: Float,
    shade: (Float) -> Float
) {
    val palmShade = shade(PALM_OUTLINE.map { points[it].z }.average().toFloat())
    val path = Path().apply {
        moveTo(mapped[PALM_OUTLINE[0]].x, mapped[PALM_OUTLINE[0]].y)
        for (i in 1 until PALM_OUTLINE.size) {
            lineTo(mapped[PALM_OUTLINE[i]].x, mapped[PALM_OUTLINE[i]].y)
        }
        close()
    }
    val pad = Stroke(width = baseWidth * 1.25f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawPath(
        path,
        color = hologramOutlineColor(palmShade),
        style = Stroke(width = baseWidth * 1.25f + 6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    drawPath(path, color = hologramGloveColor(palmShade), style = pad)
    drawPath(path, color = hologramGloveColor(palmShade))
}

private fun DrawScope.drawHologramCuff(
    center: Offset,
    z: Float,
    baseWidth: Float,
    handedness: String?,
    shade: (Float) -> Float
) {
    val cuffShade = shade(z)
    val radius = baseWidth * 0.95f
    val fill = handTint(handedness)
        ?.let { tint -> androidx.compose.ui.graphics.lerp(hologramGloveColor(cuffShade), tint, 0.55f) }
        ?: hologramGloveColor(cuffShade)
    drawCircle(color = hologramOutlineColor(cuffShade), radius = radius + 3f, center = center)
    drawCircle(color = fill, radius = radius, center = center)
}

private fun DrawScope.drawHologramFinger(
    chain: IntArray,
    mapped: List<Offset>,
    points: List<HandPoint>,
    baseWidth: Float,
    shade: (Float) -> Float
) {
    for (i in 0 until chain.size - 1) {
        val a = chain[i]
        val b = chain[i + 1]
        val segShade = shade((points[a].z + points[b].z) / 2f)
        // Afina em direcao a ponta; perto da camera fica levemente mais grosso.
        val taper = 1f - 0.12f * i
        val width = baseWidth * 0.62f * taper * (0.75f + 0.35f * segShade)

        drawLine(
            color = hologramOutlineColor(segShade),
            start = mapped[a],
            end = mapped[b],
            strokeWidth = width + 6f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = hologramGloveColor(segShade),
            start = mapped[a],
            end = mapped[b],
            strokeWidth = width,
            cap = StrokeCap.Round
        )
        val highlightOffset = Offset(-width * 0.16f, -width * 0.16f)
        drawLine(
            color = Color.White.copy(alpha = 0.35f * segShade),
            start = mapped[a] + highlightOffset,
            end = mapped[b] + highlightOffset,
            strokeWidth = width * 0.3f,
            cap = StrokeCap.Round
        )
    }
}

private fun hologramGloveColor(shade: Float): Color =
    Color(red = 0.95f * shade, green = 0.96f * shade, blue = 0.98f * shade, alpha = 0.96f)

private fun hologramOutlineColor(shade: Float): Color =
    Color(red = 0.42f * shade, green = 0.47f * shade, blue = 0.55f * shade, alpha = 0.9f)

// ---------------------------------------------------------------------------
// Compartilhado
// ---------------------------------------------------------------------------

private fun DrawScope.mapToCanvas(point: HandPoint, frame: HandTrackingFrame): Offset {
    val imageWidth = frame.imageWidth.toFloat()
    val imageHeight = frame.imageHeight.toFloat()
    var x: Float
    val y: Float
    if (imageWidth > 0f && imageHeight > 0f) {
        // Mesma geometria do PreviewView em FILL_CENTER (center-crop).
        val scale = max(size.width / imageWidth, size.height / imageHeight)
        val dx = (size.width - imageWidth * scale) / 2f
        val dy = (size.height - imageHeight * scale) / 2f
        x = point.x * imageWidth * scale + dx
        y = point.y * imageHeight * scale + dy
    } else {
        x = point.x * size.width
        y = point.y * size.height
    }
    if (frame.mirrored) {
        x = size.width - x
    }
    return Offset(x, y)
}

/**
 * Filtro passa-baixa simples por landmark para tirar o tremor do rastreamento.
 * Chaveado por handedness para sobreviver a reordenacao entre quadros.
 */
private class HandLandmarkSmoother {
    private val previous = HashMap<String, List<HandPoint>>()

    fun smooth(frame: HandTrackingFrame, factor: Float): HandTrackingFrame {
        val seen = HashSet<String>()
        val hands = frame.hands.mapIndexed { index, hand ->
            val key = hand.handedness ?: "hand-$index"
            seen += key
            val prev = previous[key]
            val smoothedPoints = if (prev != null && prev.size == hand.points.size) {
                hand.points.mapIndexed { i, point ->
                    HandPoint(
                        x = lerp(prev[i].x, point.x, factor),
                        y = lerp(prev[i].y, point.y, factor),
                        z = lerp(prev[i].z, point.z, factor)
                    )
                }
            } else {
                hand.points
            }
            previous[key] = smoothedPoints
            hand.copy(points = smoothedPoints)
        }
        previous.keys.retainAll(seen)
        return frame.copy(hands = hands)
    }

    private fun lerp(from: Float, to: Float, factor: Float): Float = from + (to - from) * factor
}
