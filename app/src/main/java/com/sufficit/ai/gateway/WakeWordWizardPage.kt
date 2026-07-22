package com.sufficit.ai.gateway

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sufficit.ai.gateway.audio.RoomAudioForegroundService
import com.sufficit.ai.gateway.audio.wake.WakeWordProfileSummary
import com.sufficit.ai.gateway.audio.wake.WakeWordStore
import com.sufficit.ai.gateway.runtime.GatewayRuntime
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.min

private const val WAKE_LAB_STEP_COUNT = 5
private const val WAKE_LAB_REQUIRED_SAMPLES = WakeWordStore.REQUIRED_SAMPLES
private val WakeLabAmber = Color(0xFFF4B942)
private val WakeLabBlue = Color(0xFF4FB8FF)
private val WakeLabPurple = Color(0xFFA78BFA)
private val WakeLabOnAccent = Color(0xFF04130C)

private enum class WakeLabStage(val step: Int) {
    INTRO(1),
    NAME(2),
    TRAIN(3),
    TEST(4),
    COMPLETE(5)
}

/**
 * Experiencia guiada de cadastro da palavra local. A tela nao cria um
 * segundo pipeline: grava, persiste e testa pelos mesmos WakeWordStore e
 * GatewayRuntime usados pelo monitor permanente de nivel 1.
 */
@Composable
fun WakeWordWizardPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val store = remember { WakeWordStore(context.applicationContext) }
    val version by GatewayRuntime.wakeWordConfigVersion().collectAsState()
    val wake by GatewayRuntime.wakeWord().collectAsState()
    val runtime by GatewayRuntime.state().collectAsState()
    val config = remember(version) { store.loadConfig() }
    val profiles = remember(version, wake.sampleCount, wake.profileCount) { store.profileSummaries() }

    var stageName by rememberSaveable { mutableStateOf(WakeLabStage.INTRO.name) }
    val stage = WakeLabStage.valueOf(stageName)
    var selectedProfileId by rememberSaveable {
        mutableStateOf(config.profiles.firstOrNull()?.id)
    }
    val selectedSummary = profiles.firstOrNull { it.profile.id == selectedProfileId }
    val selectedProfile = selectedSummary?.profile
    val sampleCount = selectedSummary?.sampleCount ?: 0
    val xpPoints = profiles.sumOf { min(it.sampleCount, WAKE_LAB_REQUIRED_SAMPLES) * 100 }
    var phraseDraft by rememberSaveable { mutableStateOf(selectedProfile?.phraseLabel.orEmpty()) }
    var lastObservedSampleCount by remember { mutableIntStateOf(sampleCount) }
    var testStartedAt by rememberSaveable { mutableLongStateOf(0L) }
    var confirmRetrain by rememberSaveable { mutableStateOf(false) }
    var createdInCurrentFlow by rememberSaveable { mutableStateOf(false) }

    val motionEnabled = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) > 0f
    }

    fun moveTo(target: WakeLabStage) {
        stageName = target.name
    }

    fun previousStage(): WakeLabStage = when (stage) {
        WakeLabStage.NAME -> WakeLabStage.INTRO
        WakeLabStage.TRAIN -> if (createdInCurrentFlow) WakeLabStage.NAME else WakeLabStage.INTRO
        WakeLabStage.TEST -> WakeLabStage.TRAIN
        WakeLabStage.COMPLETE -> WakeLabStage.TEST
        WakeLabStage.INTRO -> WakeLabStage.INTRO
    }

    fun selectProfile(profileId: String) {
        val summary = profiles.firstOrNull { it.profile.id == profileId } ?: return
        selectedProfileId = profileId
        phraseDraft = summary.profile.phraseLabel
        createdInCurrentFlow = false
    }

    fun createOrSelectProfile(): String? {
        val phrase = phraseDraft.trim()
        if (phrase.length < 2) return null
        val existing = config.profiles.firstOrNull { it.phraseLabel.equals(phrase, ignoreCase = true) }
        val profile = store.createProfile(phrase)
        selectedProfileId = profile.id
        phraseDraft = profile.phraseLabel
        createdInCurrentFlow = existing == null
        GatewayRuntime.bumpWakeWordConfigVersion()
        return profile.id
    }

    fun enableSelectedProfile(): String? {
        val profileId = selectedProfileId ?: return null
        store.updateProfile(profileId) { it.copy(enabled = true) }
        val fresh = store.loadConfig()
        if (!fresh.enabled) store.saveConfig(fresh.copy(enabled = true))
        GatewayRuntime.bumpWakeWordConfigVersion()
        return profileId
    }

    fun requestSample() {
        val profileId = enableSelectedProfile() ?: return
        if (!runtime.microphoneCaptureActive) {
            RoomAudioForegroundService.start(context)
        }
        GatewayRuntime.requestWakeWordRecording(profileId)
    }

    fun beginLiveTest() {
        if (enableSelectedProfile() == null) return
        testStartedAt = System.currentTimeMillis()
        RoomAudioForegroundService.stop(context)
    }

    BackHandler(enabled = stage != WakeLabStage.INTRO) {
        moveTo(previousStage())
    }

    LaunchedEffect(profiles, stage) {
        if (stage == WakeLabStage.INTRO && profiles.none { it.profile.id == selectedProfileId }) {
            profiles.firstOrNull()?.let { selectProfile(it.profile.id) }
        }
    }

    LaunchedEffect(selectedProfileId) {
        lastObservedSampleCount = sampleCount
    }

    LaunchedEffect(sampleCount) {
        if (sampleCount > lastObservedSampleCount) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            lastObservedSampleCount = sampleCount
            if (stage == WakeLabStage.TRAIN && sampleCount >= WAKE_LAB_REQUIRED_SAMPLES) {
                delay(if (motionEnabled) 650L else 0L)
                moveTo(WakeLabStage.TEST)
            }
        } else if (sampleCount < lastObservedSampleCount) {
            lastObservedSampleCount = sampleCount
        }
    }

    LaunchedEffect(stage) {
        if (stage == WakeLabStage.TEST && testStartedAt == 0L) {
            beginLiveTest()
        }
    }

    LaunchedEffect(wake.lastMatchAtEpochMs, testStartedAt) {
        if (
            stage == WakeLabStage.TEST &&
            testStartedAt > 0L &&
            wake.lastMatchAtEpochMs >= testStartedAt &&
            wake.lastMatchedProfileId == selectedProfileId
        ) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(if (motionEnabled) 300L else 0L)
            moveTo(WakeLabStage.COMPLETE)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF02070E), Color(0xFF071422), Color(0xFF081A1B))
                )
            )
            .padding(WindowInsets.safeDrawing.asPaddingValues())
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            WakeLabTopBar(
                stage = stage,
                xpPoints = xpPoints,
                onBack = {
                    if (stage == WakeLabStage.INTRO) onBack() else {
                        moveTo(previousStage())
                    }
                },
                onClose = onBack
            )
            WakeLabProgress(stage = stage)
            AnimatedContent(
                targetState = stage,
                transitionSpec = {
                    wizardStepTransition(forward = targetState.step > initialState.step)
                },
                label = "wake-lab-stage",
                modifier = Modifier.weight(1f)
            ) { target ->
                when (target) {
                    WakeLabStage.INTRO -> WakeLabIntroStage(
                        profiles = profiles,
                        selectedProfileId = selectedProfileId,
                        onSelectProfile = ::selectProfile,
                        onStart = {
                            val summary = profiles.firstOrNull { it.profile.id == selectedProfileId }
                            if (summary == null) {
                                phraseDraft = ""
                                createdInCurrentFlow = true
                                moveTo(WakeLabStage.NAME)
                            } else if (summary.ready) {
                                testStartedAt = 0L
                                moveTo(WakeLabStage.TEST)
                            } else {
                                moveTo(WakeLabStage.TRAIN)
                            }
                        },
                        onAdd = {
                            selectedProfileId = null
                            phraseDraft = ""
                            createdInCurrentFlow = true
                            moveTo(WakeLabStage.NAME)
                        },
                        onRetrain = { confirmRetrain = true }
                    )
                    WakeLabStage.NAME -> WakeLabNameStage(
                        phrase = phraseDraft,
                        onPhraseChange = { phraseDraft = it.take(28) },
                        onNext = {
                            if (createOrSelectProfile() != null) moveTo(WakeLabStage.TRAIN)
                        }
                    )
                    WakeLabStage.TRAIN -> WakeLabTrainStage(
                        phrase = phraseDraft,
                        sampleCount = sampleCount,
                        recording = wake.recording,
                        status = wake.status,
                        motionEnabled = motionEnabled,
                        onRecord = ::requestSample,
                        onContinue = { moveTo(WakeLabStage.TEST) }
                    )
                    WakeLabStage.TEST -> WakeLabTestStage(
                        phrase = selectedProfile?.phraseLabel ?: phraseDraft,
                        status = wake.status,
                        lastDistance = wake.lastDistance,
                        motionEnabled = motionEnabled,
                        onRetry = {
                            testStartedAt = 0L
                            beginLiveTest()
                        },
                        onSkip = { moveTo(WakeLabStage.COMPLETE) }
                    )
                    WakeLabStage.COMPLETE -> WakeLabCompleteStage(
                        phrase = selectedProfile?.phraseLabel ?: phraseDraft,
                        sampleCount = sampleCount,
                        threshold = selectedProfile?.threshold ?: wake.threshold,
                        autoThreshold = selectedProfile?.autoThreshold ?: true,
                        onAutoThresholdChange = { automatic ->
                            selectedProfileId?.let { profileId ->
                                store.updateProfile(profileId) { it.copy(autoThreshold = automatic) }
                            }
                            GatewayRuntime.bumpWakeWordConfigVersion()
                        },
                        onThresholdChange = { value ->
                            selectedProfileId?.let { profileId ->
                                store.updateProfile(profileId) {
                                    it.copy(
                                        threshold = value.toDouble(),
                                        autoThreshold = false
                                    )
                                }
                            }
                            GatewayRuntime.bumpWakeWordConfigVersion()
                        },
                        onTestAgain = {
                            testStartedAt = 0L
                            moveTo(WakeLabStage.TEST)
                        },
                        onAddAnother = {
                            selectedProfileId = null
                            phraseDraft = ""
                            createdInCurrentFlow = true
                            moveTo(WakeLabStage.NAME)
                        },
                        onFinish = onBack
                    )
                }
            }
        }
    }

    if (confirmRetrain) {
        AlertDialog(
            onDismissRequest = { confirmRetrain = false },
            title = { Text("Treinar novamente '${selectedProfile?.phraseLabel.orEmpty()}'?") },
            text = {
                Text(
                    "Somente as chaves desta wake word serao apagadas. As outras chamadas " +
                        "continuam ativas, e esta voltara apos tres novas gravacoes validas."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        selectedProfileId?.let(store::clearSamples)
                        GatewayRuntime.bumpWakeWordConfigVersion()
                        confirmRetrain = false
                        createdInCurrentFlow = false
                        moveTo(WakeLabStage.TRAIN)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ConfigTheme.Danger)
                ) {
                    Text("Apagar 3 chaves")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRetrain = false }) { Text("Cancelar") }
            },
            containerColor = ConfigTheme.Surface,
            titleContentColor = ConfigTheme.TextPrimary,
            textContentColor = ConfigTheme.TextSecondary
        )
    }
}

@Composable
private fun WakeLabTopBar(
    stage: WakeLabStage,
    xpPoints: Int,
    onBack: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = if (stage == WakeLabStage.INTRO) "Voltar para configuracao" else "Missao anterior",
                tint = ConfigTheme.TextPrimary
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "WAKE LAB",
                color = ConfigTheme.Accent,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Missao ${stage.step} de $WAKE_LAB_STEP_COUNT",
                color = ConfigTheme.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        WakeLabXpBadge(points = xpPoints)
        TextButton(onClick = onClose, modifier = Modifier.height(48.dp)) {
            Text("Sair", color = ConfigTheme.TextSecondary)
        }
    }
}

@Composable
private fun WakeLabProgress(stage: WakeLabStage) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        WizardStepIndicator(currentStep = stage.step, totalSteps = WAKE_LAB_STEP_COUNT)
        Text(
            text = when (stage) {
                WakeLabStage.INTRO -> "Descobrir"
                WakeLabStage.NAME -> "Nomear"
                WakeLabStage.TRAIN -> "Treinar"
                WakeLabStage.TEST -> "Despertar"
                WakeLabStage.COMPLETE -> "Conquista"
            },
            color = ConfigTheme.TextMuted,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.End)
        )
    }
}

@Composable
private fun WakeLabIntroStage(
    profiles: List<WakeWordProfileSummary>,
    selectedProfileId: String?,
    onSelectProfile: (String) -> Unit,
    onStart: () -> Unit,
    onAdd: () -> Unit,
    onRetrain: () -> Unit
) {
    val selected = profiles.firstOrNull { it.profile.id == selectedProfileId }
    val readyCount = profiles.count { it.ready }
    val profileNoun = if (profiles.size == 1) "chamada pronta" else "chamadas prontas"
    val descriptionNoun = if (profiles.size == 1) "chamada cadastrada" else "chamadas cadastradas"
    WakeLabStageList {
        item {
            WakeLabHeroOrb(
                active = false,
                success = readyCount > 0,
                motionEnabled = false,
                description = "Nucleo visual de ${profiles.size} $descriptionNoun"
            )
        }
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (profiles.isNotEmpty()) {
                        "Seu arsenal de chamadas"
                    } else {
                        "Dê uma identidade à sua sala"
                    },
                    color = ConfigTheme.TextPrimary,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (profiles.isNotEmpty()) {
                        "$readyCount de ${profiles.size} $profileNoun. Selecione uma para testar ou crie quantas quiser."
                    } else {
                        "Você escolhe a chamada. Cada wake word aprende três variações da sua voz e fica inteiramente no aparelho."
                    },
                    color = ConfigTheme.TextSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
        }
        if (profiles.isEmpty()) {
            item { WakeLabFeatureRow() }
        } else {
            profiles.forEach { summary ->
                item(key = summary.profile.id) {
                    WakeLabProfileCard(
                        summary = summary,
                        selected = summary.profile.id == selectedProfileId,
                        onClick = { onSelectProfile(summary.profile.id) }
                    )
                }
            }
        }
        item {
            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ConfigTheme.Accent,
                    contentColor = WakeLabOnAccent
                )
            ) {
                Text(
                    when {
                        selected == null -> "Criar minha primeira wake word"
                        selected.ready -> "Testar '${selected.profile.phraseLabel}'"
                        else -> "Completar '${selected.profile.phraseLabel}'"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        }
        if (profiles.isNotEmpty()) {
            item {
                OutlinedButton(
                    onClick = onAdd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Adicionar outra wake word")
                }
            }
        }
        if (selected != null && selected.sampleCount > 0) {
            item {
                TextButton(
                    onClick = onRetrain,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Gravar novamente '${selected.profile.phraseLabel}'")
                }
            }
        }
    }
}

@Composable
private fun WakeLabProfileCard(
    summary: WakeWordProfileSummary,
    selected: Boolean,
    onClick: () -> Unit
) {
    val completed = min(summary.sampleCount, WAKE_LAB_REQUIRED_SAMPLES)
    val status = when {
        summary.ready -> "Pronta"
        completed == 0 -> "Sem treinamento"
        else -> "Falta ${WAKE_LAB_REQUIRED_SAMPLES - completed}"
    }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Wake word ${summary.profile.phraseLabel}, $completed de $WAKE_LAB_REQUIRED_SAMPLES gravacoes, $status"
            },
        shape = RoundedCornerShape(18.dp),
        color = if (selected) ConfigTheme.Accent.copy(alpha = 0.14f) else ConfigTheme.Surface,
        border = androidx.compose.foundation.BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) ConfigTheme.Accent else ConfigTheme.Border
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (summary.ready) ConfigTheme.Accent.copy(alpha = 0.18f)
                        else WakeLabAmber.copy(alpha = 0.16f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.Star,
                    contentDescription = null,
                    tint = if (summary.ready) ConfigTheme.Accent else WakeLabAmber
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = summary.profile.phraseLabel,
                    color = ConfigTheme.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "$completed/$WAKE_LAB_REQUIRED_SAMPLES chaves de voz • $status",
                    color = if (summary.ready) ConfigTheme.Accent else ConfigTheme.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (selected) {
                Text(
                    text = "SELECIONADA",
                    color = ConfigTheme.Accent,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun WakeLabNameStage(
    phrase: String,
    onPhraseChange: (String) -> Unit,
    onNext: () -> Unit
) {
    WakeLabStageList {
        item {
            WakeLabMissionCard(
                eyebrow = "MISSÃO 02",
                title = "Escolha sua palavra-chave",
                body = "Curta, marcante e diferente das palavras comuns da sala. Duas ou três sílabas funcionam melhor."
            )
        }
        item {
            OutlinedTextField(
                value = phrase,
                onValueChange = onPhraseChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Como você vai chamar o agente?") },
                supportingText = { Text("Ex.: xuxu, Sufficit ou um apelido exclusivo") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                colors = configTextFieldColors()
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("xuxu", "Sufficit", "sentinela").forEach { suggestion ->
                    Surface(
                        onClick = { onPhraseChange(suggestion) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        color = if (phrase.equals(suggestion, true)) {
                            ConfigTheme.Accent.copy(alpha = 0.18f)
                        } else {
                            ConfigTheme.Surface
                        },
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (phrase.equals(suggestion, true)) ConfigTheme.Accent else ConfigTheme.Border
                        )
                    ) {
                        Text(
                            text = suggestion,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 14.dp),
                            color = ConfigTheme.TextPrimary,
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
        item {
            WakeLabTip("Evite nomes parecidos com palavras repetidas na TV, música ou conversas do ambiente.")
        }
        item {
            Button(
                onClick = onNext,
                enabled = phrase.trim().length >= 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ConfigTheme.Accent,
                    contentColor = WakeLabOnAccent
                )
            ) {
                Text("Confirmar chamada", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun WakeLabTrainStage(
    phrase: String,
    sampleCount: Int,
    recording: Boolean,
    status: String,
    motionEnabled: Boolean,
    onRecord: () -> Unit,
    onContinue: () -> Unit
) {
    val completed = min(sampleCount, WAKE_LAB_REQUIRED_SAMPLES)
    WakeLabStageList {
        item {
            WakeLabHeroOrb(
                active = recording,
                success = completed >= WAKE_LAB_REQUIRED_SAMPLES,
                motionEnabled = motionEnabled,
                description = if (recording) "Gravando palavra de ativacao" else "Pronto para gravar"
            )
        }
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = if (recording) "Fale agora" else "Diga “$phrase”",
                    color = if (recording) WakeLabAmber else ConfigTheme.TextPrimary,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (recording) {
                        "Naturalmente, olhando para o aparelho"
                    } else {
                        "Cada chave ensina uma variação da sua voz"
                    },
                    color = ConfigTheme.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
        item { WakeLabSampleKeys(completed = completed, recording = recording) }
        item {
            WakeLabTip(
                when (completed) {
                    0 -> "Primeira chave: use seu tom normal."
                    1 -> "Segunda chave: fale um pouco mais baixo ou mais distante."
                    else -> "Terceira chave: repita como chamaria o agente naturalmente."
                }
            )
        }
        item {
            Button(
                onClick = if (completed >= WAKE_LAB_REQUIRED_SAMPLES) onContinue else onRecord,
                enabled = !recording,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (completed >= WAKE_LAB_REQUIRED_SAMPLES) ConfigTheme.Accent else WakeLabAmber,
                    contentColor = WakeLabOnAccent
                )
            ) {
                if (recording) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = WakeLabOnAccent
                    )
                    Spacer(Modifier.size(10.dp))
                    Text("Capturando chave...", fontWeight = FontWeight.Bold)
                } else {
                    Text(
                        if (completed >= WAKE_LAB_REQUIRED_SAMPLES) "Ir para o teste ao vivo" else "Gravar chave ${completed + 1}",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        item {
            Text(
                text = status,
                modifier = Modifier.fillMaxWidth(),
                color = ConfigTheme.TextMuted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun WakeLabTestStage(
    phrase: String,
    status: String,
    lastDistance: Double?,
    motionEnabled: Boolean,
    onRetry: () -> Unit,
    onSkip: () -> Unit
) {
    WakeLabStageList {
        item {
            WakeLabHeroOrb(
                active = true,
                success = false,
                motionEnabled = motionEnabled,
                description = "Monitor local aguardando a palavra $phrase"
            )
        }
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WakeLabBadge("NÍVEL 1 ATIVO", ConfigTheme.Accent)
                Text(
                    text = "Agora me acorde",
                    color = ConfigTheme.TextPrimary,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Diga “$phrase” sem tocar em nada. O monitor local deve reconhecer sua voz e liberar a escuta ambiente.",
                    color = ConfigTheme.TextSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
        }
        item {
            WakeLabMissionCard(
                eyebrow = "TELEMETRIA LOCAL",
                title = lastDistance?.let { "Distância atual: ${String.format(Locale.US, "%.2f", it)}" }
                    ?: "Aguardando sua voz",
                body = status
            )
        }
        item {
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Reiniciar teste")
            }
        }
        item {
            TextButton(
                onClick = onSkip,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Concluir sem teste", color = ConfigTheme.TextSecondary)
            }
        }
    }
}

@Composable
private fun WakeLabCompleteStage(
    phrase: String,
    sampleCount: Int,
    threshold: Double,
    autoThreshold: Boolean,
    onAutoThresholdChange: (Boolean) -> Unit,
    onThresholdChange: (Float) -> Unit,
    onTestAgain: () -> Unit,
    onAddAnother: () -> Unit,
    onFinish: () -> Unit
) {
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var thresholdDraft by remember(threshold) { mutableStateOf(threshold.toFloat()) }
    WakeLabStageList {
        item {
            WakeLabHeroOrb(
                active = false,
                success = true,
                motionEnabled = false,
                description = "Treinamento concluido"
            )
        }
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WakeLabBadge("+${min(sampleCount, WAKE_LAB_REQUIRED_SAMPLES) * 100} XP", WakeLabAmber)
                Text(
                    text = "Despertar conquistado",
                    color = ConfigTheme.TextPrimary,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "“$phrase” agora acorda o aplicativo localmente, mesmo quando a escuta ambiente está pausada.",
                    color = ConfigTheme.TextSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                WakeLabStat("CHAVES", "$sampleCount", Modifier.weight(1f))
                WakeLabStat("LIMIAR", String.format(Locale.US, "%.2f", threshold), Modifier.weight(1f))
                WakeLabStat("MODO", if (autoThreshold) "AUTO" else "MANUAL", Modifier.weight(1f))
            }
        }
        item {
            Surface(
                onClick = { advancedOpen = !advancedOpen },
                modifier = Modifier.fillMaxWidth(),
                color = ConfigTheme.Surface,
                shape = RoundedCornerShape(ConfigTheme.RadiusCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, ConfigTheme.Border)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Ajuste fino", color = ConfigTheme.TextPrimary, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (advancedOpen) "Controles técnicos visíveis" else "Automático é recomendado",
                                color = ConfigTheme.TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(if (advancedOpen) "Fechar" else "Abrir", color = ConfigTheme.Accent)
                    }
                    if (advancedOpen) {
                        HorizontalDivider(color = ConfigTheme.Border)
                        SettingToggleRow(
                            title = "Limiar automático",
                            supportingText = "Calcula a sensibilidade pela variação das chaves gravadas.",
                            checked = autoThreshold,
                            onCheckedChange = onAutoThresholdChange
                        )
                        Text(
                            "Sensibilidade manual: ${String.format(Locale.US, "%.2f", thresholdDraft)}",
                            color = ConfigTheme.TextPrimary,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Slider(
                            value = thresholdDraft,
                            onValueChange = { thresholdDraft = it },
                            onValueChangeFinished = { onThresholdChange(thresholdDraft) },
                            enabled = !autoThreshold,
                            valueRange = 0.05f..0.5f
                        )
                    }
                }
            }
        }
        item {
            Button(
                onClick = onFinish,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ConfigTheme.Accent,
                    contentColor = WakeLabOnAccent
                )
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Concluir Wake Lab", fontWeight = FontWeight.Bold)
            }
        }
        item {
            OutlinedButton(
                onClick = onAddAnother,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Adicionar outra wake word")
            }
        }
        item {
            TextButton(
                onClick = onTestAgain,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Testar novamente", color = ConfigTheme.TextSecondary)
            }
        }
    }
}

@Composable
private fun WakeLabStageList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

@Composable
private fun WakeLabHeroOrb(
    active: Boolean,
    success: Boolean,
    motionEnabled: Boolean,
    description: String
) {
    val infinite = rememberInfiniteTransition(label = "wake-orb")
    val animatedPulse by infinite.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wake-orb-pulse"
    )
    val pulse = if (active && motionEnabled) animatedPulse else 1f
    val accent = if (success) ConfigTheme.Accent else if (active) WakeLabAmber else WakeLabBlue

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(202.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(172.dp)
                .scale(pulse)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.28f), Color(0xFF0A1825), Color(0xFF050B12))
                    )
                )
                .border(2.dp, accent.copy(alpha = 0.75f), CircleShape)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(128.dp)) {
                drawCircle(
                    color = accent.copy(alpha = 0.22f),
                    radius = size.minDimension * 0.47f,
                    style = Stroke(width = 3f)
                )
                val centerY = size.height / 2f
                val barWidth = size.width / 28f
                val gap = barWidth * 0.9f
                val heights = listOf(0.18f, 0.34f, 0.58f, 0.82f, 0.48f, 0.9f, 0.62f, 0.38f, 0.2f)
                val totalWidth = heights.size * barWidth + (heights.size - 1) * gap
                var x = (size.width - totalWidth) / 2f
                heights.forEachIndexed { index, factor ->
                    val liveFactor = if (active) {
                        (factor * (0.88f + ((index % 3) * 0.09f)) * pulse).coerceAtMost(1f)
                    } else {
                        factor
                    }
                    val height = size.height * 0.56f * liveFactor
                    drawLine(
                        color = accent,
                        start = Offset(x, centerY - height / 2f),
                        end = Offset(x, centerY + height / 2f),
                        strokeWidth = barWidth,
                        cap = StrokeCap.Round
                    )
                    x += barWidth + gap
                }
            }
            if (success) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = ConfigTheme.Accent,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(18.dp)
                        .size(34.dp)
                        .background(Color(0xFF06110D), CircleShape)
                )
            }
        }
    }
}

@Composable
private fun WakeLabFeatureRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        WakeLabFeature("LOCAL", "Sem nuvem", ConfigTheme.Accent, Modifier.weight(1f))
        WakeLabFeature("PRIVADO", "Sua voz", WakeLabBlue, Modifier.weight(1f))
        WakeLabFeature("SEMPRE", "Nível 1", WakeLabPurple, Modifier.weight(1f))
    }
}

@Composable
private fun WakeLabFeature(label: String, detail: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(ConfigTheme.Surface, RoundedCornerShape(16.dp))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(horizontal = 8.dp, vertical = 13.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(label, color = accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Text(detail, color = ConfigTheme.TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun WakeLabSampleKeys(completed: Int, recording: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        repeat(WAKE_LAB_REQUIRED_SAMPLES) { index ->
            val done = index < completed
            val current = index == completed && recording
            val accent = when {
                done -> ConfigTheme.Accent
                current -> WakeLabAmber
                else -> ConfigTheme.TextMuted
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(ConfigTheme.Surface, RoundedCornerShape(16.dp))
                    .border(1.dp, accent.copy(alpha = if (done || current) 0.75f else 0.3f), RoundedCornerShape(16.dp))
                    .padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                if (done) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = "Chave ${index + 1} concluida", tint = accent)
                } else if (current) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = accent)
                } else {
                    Icon(Icons.Filled.Star, contentDescription = "Chave ${index + 1} pendente", tint = accent)
                }
                Text("CHAVE ${index + 1}", color = accent, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun WakeLabMissionCard(eyebrow: String, title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ConfigTheme.Surface, RoundedCornerShape(ConfigTheme.RadiusCard))
            .border(1.dp, ConfigTheme.Border, RoundedCornerShape(ConfigTheme.RadiusCard))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(eyebrow, color = ConfigTheme.Accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Text(title, color = ConfigTheme.TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(body, color = ConfigTheme.TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WakeLabTip(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WakeLabBlue.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
            .border(1.dp, WakeLabBlue.copy(alpha = 0.28f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Star, contentDescription = null, tint = WakeLabBlue, modifier = Modifier.size(20.dp))
        Text(text, color = ConfigTheme.TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun WakeLabBadge(text: String, color: Color) {
    Text(
        text = text,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), CircleShape)
            .border(1.dp, color.copy(alpha = 0.55f), CircleShape)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        color = color,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun WakeLabXpBadge(points: Int) {
    WakeLabBadge(text = "$points XP", color = WakeLabAmber)
}

@Composable
private fun WakeLabStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(ConfigTheme.Surface, RoundedCornerShape(14.dp))
            .border(1.dp, ConfigTheme.Border, RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(value, color = ConfigTheme.TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, color = ConfigTheme.TextMuted, style = MaterialTheme.typography.labelSmall)
    }
}
