package com.noc.app.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.noc.app.ui.components.pressable
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.noc.app.AppContainer
import com.noc.app.core.net.PairingFailed
import com.noc.app.core.net.PairingLink
import com.noc.app.ui.components.GhostButton
import com.noc.app.ui.components.PrimaryButton
import com.noc.app.ui.components.SecondaryButton
import com.noc.app.ui.components.TopBar
import com.noc.app.ui.theme.GeistMono
import com.noc.app.ui.theme.Noc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

private sealed interface PairState {
    data object Idle : PairState
    data object Working : PairState
    data class Confirm(val sas: String) : PairState
    data class Failed(val message: String) : PairState
}

fun pairError(reason: String): String = when (reason) {
    "expired" -> "Esse código expirou. Gere um novo no Companion."
    "unknown_pairing", "unknown" -> "Esse QR já foi usado ou não é mais válido. Gere um novo no Companion."
    "rejected" -> "O pareamento foi recusado no PC (ou o tempo para aprovar acabou)."
    "bad_proof" -> "Código incorreto. Confira as letras e tente de novo."
    "code_not_found" -> "Código não encontrado. Confira ou gere outro no Companion."
    "unreachable" -> "Não consegui alcançar o PC. Verifique se o Noc Companion está aberto e com internet."
    "pc_offline" -> "O PC não está conectado agora. Abra o Noc Companion e tente de novo."
    "no_internet" -> "Sem internet no celular."
    "relay" -> "O serviço de conexão não respondeu. Tente em alguns segundos."
    "rate" -> "Muitas tentativas seguidas. Aguarde um minuto."
    "timeout" -> "O PC não respondeu a tempo."
    "security" -> "A identidade do PC não confere. Por segurança, o pareamento foi cancelado."
    "bad_code" -> "O código tem 8 caracteres, como K7M4-Q2XP."
    "rate_limited" -> "Muitas tentativas. Aguarde alguns minutos."
    else -> "Não foi possível parear. Tente de novo."
}

@Composable
fun PairScreen(container: AppContainer, initialLink: String?, onBack: () -> Unit, onPaired: () -> Unit) {
    val c = Noc.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<PairState>(PairState.Idle) }
    var codeMode by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }

    fun pairLink(raw: String) {
        if (state is PairState.Working || state is PairState.Confirm) return
        val link = PairingLink.parse(raw)
        if (link == null) {
            state = PairState.Failed("Esse QR não é de um Noc Companion.")
            return
        }
        state = PairState.Working
        job = scope.launch {
            try {
                container.connection.pairWithLink(link)
                onPaired()
            } catch (e: PairingFailed) {
                state = PairState.Failed(pairError(e.reason))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = PairState.Failed(pairError("unreachable"))
            }
        }
    }

    fun pairCode() {
        val clean = code.uppercase().filter { it.isLetterOrDigit() }
        if (clean.length != 8) { state = PairState.Failed(pairError("bad_code")); return }
        state = PairState.Working
        job = scope.launch {
            try {
                container.connection.pairWithCode(clean) { sas -> state = PairState.Confirm(sas) }
                onPaired()
            } catch (e: PairingFailed) {
                state = PairState.Failed(pairError(e.reason))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = PairState.Failed(pairError("unreachable"))
            }
        }
    }

    LaunchedEffect(initialLink) { initialLink?.let { pairLink(it) } }

    Column(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        TopBar("", onBack = { job?.cancel(); onBack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Text("Parear com\nseu computador", style = MaterialTheme.typography.displayMedium, color = c.text)
            Spacer(Modifier.height(10.dp))
            Text(
                if (codeMode) "No Noc Companion, toque em “Parear celular” e depois em “Código”. Digite aqui o código que aparecer."
                else "No PC, abra o Noc Companion e toque em “Parear celular”. Depois aponte a câmera para o QR Code.",
                style = MaterialTheme.typography.bodyLarge, color = c.text2,
            )
            Spacer(Modifier.height(24.dp))

            AnimatedContent(state, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "pair") { s ->
                when (s) {
                    PairState.Working -> Status(title = "Conectando ao PC…", body = "Trocando chaves de segurança. Leva só alguns segundos.", progress = true)
                    is PairState.Confirm -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Código de verificação", style = MaterialTheme.typography.labelMedium, color = c.text3)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            s.sas.chunked(3).joinToString(" "),
                            style = TextStyle(fontFamily = GeistMono, fontSize = 44.sp, letterSpacing = 4.sp),
                            color = c.text,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Confira se o mesmo número aparece no PC e toque em “Aprovar” lá. Isso garante que ninguém está no meio da conexão.",
                            style = MaterialTheme.typography.bodyMedium, color = c.text2, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    }
                    else -> Column {
                        if (!codeMode) {
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(28.dp)).background(c.surface),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (hasCamera) {
                                    QrScanner(onCode = ::pairLink)
                                    // moldura
                                    Box(Modifier.fillMaxWidth(0.62f).aspectRatio(1f).border(2.dp, c.accent.copy(alpha = 0.9f), RoundedCornerShape(24.dp)))
                                } else {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                                        Icon(Icons.Rounded.QrCodeScanner, null, tint = c.text2, modifier = Modifier.size(40.dp))
                                        Spacer(Modifier.height(12.dp))
                                        Text("Precisamos da câmera para ler o QR Code.", style = MaterialTheme.typography.bodyMedium, color = c.text2, textAlign = TextAlign.Center)
                                        Spacer(Modifier.height(14.dp))
                                        SecondaryButton("Permitir câmera", icon = Icons.Rounded.CameraAlt) { permission.launch(Manifest.permission.CAMERA) }
                                    }
                                }
                            }
                        } else {
                            CodeInput(code, onChange = { code = it; if (state is PairState.Failed) state = PairState.Idle }, onDone = ::pairCode)
                            Spacer(Modifier.height(16.dp))
                            PrimaryButton("Parear", Modifier.fillMaxWidth(), enabled = code.filter { it.isLetterOrDigit() }.length == 8) { pairCode() }
                        }
                        (s as? PairState.Failed)?.let { f ->
                            Spacer(Modifier.height(14.dp))
                            Text(f.message, style = MaterialTheme.typography.bodyMedium, color = c.err)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
            if (state !is PairState.Working && state !is PairState.Confirm) {
                GhostButton(if (codeMode) "Ler QR Code" else "Digitar código", color = c.text) {
                    codeMode = !codeMode
                    state = PairState.Idle
                }
            } else {
                GhostButton("Cancelar", color = c.text2) { job?.cancel(); state = PairState.Idle }
            }
        }
    }
}

@Composable
private fun Status(title: String, body: String, progress: Boolean) {
    val c = Noc.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surface).padding(24.dp)) {
        if (progress) CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = c.text)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = c.text2)
    }
}

@Composable
private fun CodeInput(value: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    val c = Noc.colors
    val clean = value.uppercase().filter { it.isLetterOrDigit() }.take(8)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)
            .pressable { runCatching { focus.requestFocus() }; keyboard?.show() }
            .padding(vertical = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (clean.isEmpty()) Text("XXXX-XXXX", style = TextStyle(fontFamily = GeistMono, fontSize = 34.sp, letterSpacing = 4.sp), color = c.text3.copy(alpha = 0.5f))
        BasicTextField(
            value = clean,
            onValueChange = { onChange(it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(8)) },
            visualTransformation = CodeDash,
            singleLine = true,
            textStyle = TextStyle(fontFamily = GeistMono, fontSize = 34.sp, letterSpacing = 4.sp, color = c.text, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Keyboard, null, tint = c.text3, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text("O código vale por 5 minutos e só funciona uma vez.", style = MaterialTheme.typography.bodySmall, color = c.text3)
    }
}

/** Mostra "ABCD-EFGH" sem colocar o hífen no texto real (o cursor não se perde). */
private object CodeDash : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val out = if (raw.length > 4) raw.substring(0, 4) + "-" + raw.substring(4) else raw
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = if (offset > 4) offset + 1 else offset
            override fun transformedToOriginal(offset: Int) = if (offset > 4) (offset - 1).coerceAtMost(raw.length) else offset.coerceAtMost(raw.length)
        }
        return TransformedText(AnnotatedString(out), mapping)
    }
}

@Composable
private fun QrScanner(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    var delivered by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            scanner.close()
            executor.shutdown()
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null || delivered) { proxy.close(); return@setAnalyzer }
                    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { codes ->
                            val raw = codes.firstNotNullOfOrNull { it.rawValue?.takeIf { v -> v.startsWith("noc://") } }
                            if (raw != null && !delivered) {
                                delivered = true
                                ContextCompat.getMainExecutor(ctx).execute { onCode(raw) }
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(ctx))
            view
        },
    )
}
