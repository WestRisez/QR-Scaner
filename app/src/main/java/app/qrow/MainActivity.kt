package app.qrow

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var db: Db

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        db = Db(applicationContext)
        setContent {
            QrTheme { App(db) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) db.close()
    }
}

// ---------- Тема ----------

private val LightColors = lightColorScheme(
    primary = Color(0xFF3F51B5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF),
    onPrimaryContainer = Color(0xFF001258),
    secondaryContainer = Color(0xFFE0E0F9),
    background = Color(0xFFFBF8FF),
    surface = Color(0xFFFBF8FF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB8C3FF),
    onPrimary = Color(0xFF0B2087),
    primaryContainer = Color(0xFF283598),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondaryContainer = Color(0xFF424659),
    background = Color(0xFF121318),
    surface = Color(0xFF121318),
)

@Composable
private fun QrTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

// ---------- Вспомогательное ----------

private fun dateFmt() = SimpleDateFormat("dd MMM, HH:mm:ss", Locale.getDefault())
private fun dateFmtFull() = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())

private fun formatName(format: Int): String = when (format) {
    Barcode.FORMAT_QR_CODE -> "QR"
    Barcode.FORMAT_EAN_13 -> "EAN-13"
    Barcode.FORMAT_EAN_8 -> "EAN-8"
    Barcode.FORMAT_CODE_128 -> "Code 128"
    Barcode.FORMAT_CODE_39 -> "Code 39"
    Barcode.FORMAT_UPC_A -> "UPC-A"
    Barcode.FORMAT_UPC_E -> "UPC-E"
    Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
    Barcode.FORMAT_PDF417 -> "PDF417"
    Barcode.FORMAT_AZTEC -> "Aztec"
    else -> "Другое"
}

// ---------- Корневой экран ----------

@Composable
private fun App(db: Db) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var scans by remember { mutableStateOf(db.all()) }
    var lastScanned by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(value = false) }

    // Защита от повторной записи одного и того же кода подряд
    val lastValue = remember { arrayOf<String?>(null) }
    val lastTime = remember { longArrayOf(0L) }
    val tone = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }
    DisposableEffect(Unit) { onDispose { tone.release() } }

    fun onScan(content: String, format: String) {
        val now = System.currentTimeMillis()
        if ((content == lastValue[0]) && ((now - lastTime[0]) < 3000)) {
            lastTime[0] = now
            return
        }
        lastValue[0] = content
        lastTime[0] = now
        db.insert(content, format)
        scans = db.all()
        lastScanned = content
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Rounded.QrCodeScanner, null) },
                    label = { Text("Сканер") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (scans.isNotEmpty()) Badge { Text(scans.size.toString()) }
                            },
                        ) { Icon(Icons.Rounded.TableChart, null) }
                    },
                    label = { Text("Таблица") },
                )
            }
        },
        floatingActionButton = {
            if ((tab == 1) && scans.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { exportXlsx(context, scans) },
                    icon = { Icon(Icons.Rounded.FileDownload, null) },
                    text = { Text("Экспорт в Excel") },
                )
            }
        }
    ) { padding ->
        // Камера заходит под статус-бар, поэтому сверху отступ не применяем
        Box(Modifier.padding(bottom = padding.calculateBottomPadding()).fillMaxSize()) {
            if (tab == 0) {
                ScannerScreen(lastScanned, ::onScan)
            } else {
                TableScreen(
                    scans = scans,
                    onDelete = { id -> db.delete(id); scans = db.all() },
                ) { confirmClear = true }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Rounded.DeleteSweep, null) },
            title = { Text("Очистить таблицу?") },
            text = { Text("Будут удалены все записи (${scans.size}). Это нельзя отменить.") },
            confirmButton = {
                TextButton(onClick = {
                    db.clear()
                    scans = emptyList()
                    lastValue[0] = null
                    lastScanned = null
                    confirmClear = false
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } }
        )
    }
}

// ---------- Сканер ----------

@Composable
private fun ScannerScreen(lastScanned: String?, onScan: (String, String) -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    Icons.Rounded.CameraAlt, null,
                    Modifier.padding(24.dp).size(40.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(24.dp))
            Text("Нужен доступ к камере", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Камера используется только для считывания QR-кодов.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                Text("Разрешить")
            }
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        var camera by remember { mutableStateOf<Camera?>(null) }
        var torchOn by remember { mutableStateOf(value = false) }
        var zoom by remember { mutableFloatStateOf(1f) }

        CameraPreview(onScan, onCamera = {
            camera = it
            torchOn = false
            zoom = 1f
        })
        ScanOverlay()

        val cam = camera
        val hasFlash = cam?.cameraInfo?.hasFlashUnit() == true
        var maxZoomRatio by remember { mutableFloatStateOf(1f) }
        DisposableEffect(cam) {
            val liveData = cam?.cameraInfo?.zoomState
            val observer = androidx.lifecycle.Observer<androidx.camera.core.ZoomState> {
                maxZoomRatio = it.maxZoomRatio
            }
            liveData?.observeForever(observer)
            onDispose { liveData?.removeObserver(observer) }
        }
        val maxZoom = minOf(maxZoomRatio, 8f)
        val overlayButtons = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = Color.Black.copy(alpha = 0.4f),
            contentColor = Color.White
        )

        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalIconButton(
                onClick = {
                    torchOn = !torchOn
                    cam?.cameraControl?.enableTorch(torchOn)
                },
                enabled = hasFlash,
                colors = if (torchOn) IconButtonDefaults.filledIconButtonColors() else overlayButtons
            ) {
                Icon(
                    if (torchOn) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                    contentDescription = "Фонарик"
                )
            }
            Text(
                "Наведите камеру на QR-код",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            // Пустышка той же ширины, чтобы подсказка оставалась по центру
            Spacer(Modifier.size(48.dp))
        }

        if (maxZoom > 1.05f) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = if (lastScanned != null) 112.dp else 24.dp),
                shape = RoundedCornerShape(28.dp),
                color = Color.Black.copy(alpha = 0.4f)
            ) {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("1×", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = zoom,
                        onValueChange = {
                            zoom = it
                            cam?.cameraControl?.setZoomRatio(it)
                        },
                        valueRange = 1f..maxZoom,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(
                            inactiveTrackColor = Color.White.copy(alpha = 0.35f)
                        )
                    )
                    Text(
                        String.format(Locale.US, "%.1f×", zoom),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.width(40.dp),
                        textAlign = TextAlign.End
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = lastScanned != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut()
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.CheckCircle, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Добавлено в таблицу",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            lastScanned.orEmpty(),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** Затемнение с «окном» видоискателя и бегущей линией. */
@Composable
private fun ScanOverlay() {
    val accent = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "scan")
    val progress by transition.animateFloat(
        initialValue = 0.05f, targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Reverse),
        label = "line"
    )
    Canvas(
        Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    ) {
        val side = minOf(size.width, size.height) * 0.68f
        val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f - size.height * 0.04f)
        val radius = CornerRadius(28.dp.toPx())

        drawRect(Color.Black.copy(alpha = 0.55f))
        drawRoundRect(Color.Black, topLeft, Size(side, side), radius, blendMode = BlendMode.Clear)
        drawRoundRect(
            Color.White.copy(alpha = 0.9f), topLeft, Size(side, side), radius,
            style = Stroke(width = 3.dp.toPx())
        )
        val y = topLeft.y + side * progress
        val inset = 20.dp.toPx()
        drawLine(
            accent, Offset(topLeft.x + inset, y), Offset(topLeft.x + side - inset, y),
            strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round
        )
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraPreview(onScan: (String, String) -> Unit, onCamera: (Camera) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnScan by rememberUpdatedState(onScan)
    val currentOnCamera by rememberUpdatedState(onCamera)
    val scanner = remember { BarcodeScanning.getClient() }
    val providerHolder = remember { arrayOfNulls<ProcessCameraProvider>(1) }
    DisposableEffect(Unit) {
        onDispose {
            // Отпускаем камеру (и фонарик) при уходе с вкладки «Сканер»
            providerHolder[0]?.unbindAll()
            scanner.close()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { proxy ->
                    @OptIn(ExperimentalGetImage::class)
                    val media = proxy.image
                    if (media == null) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { codes ->
                            codes.firstOrNull { !it.rawValue.isNullOrEmpty() }?.let {
                                currentOnScan(it.rawValue!!, formatName(it.format))
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                providerHolder[0] = provider
                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
                currentOnCamera(camera)
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
}

// ---------- Таблица ----------

@Composable
private fun TableScreen(scans: List<Scan>, onDelete: (Long) -> Unit, onClear: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Записи", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    if (scans.isEmpty()) "Пока ничего не отсканировано" else "Всего: ${scans.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (scans.isNotEmpty()) {
                FilledTonalIconButton(onClick = onClear) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = "Очистить таблицу")
                }
            }
        }

        if (scans.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(
                        Icons.Rounded.QrCodeScanner, null,
                        Modifier.padding(28.dp).size(48.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text("Здесь появятся ваши коды", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Перейдите на вкладку «Сканер» и наведите камеру на QR-код.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(scans, key = { it.id }) { scan ->
                val number = scans.size - scans.indexOf(scan)
                ScanCard(
                    number = number,
                    scan = scan,
                    onCopy = {
                        clipboard.setText(AnnotatedString(scan.content))
                        Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                    },
                    onDelete = { onDelete(scan.id) }
                )
            }
        }
    }
}

@Composable
private fun ScanCard(number: Int, scan: Scan, onCopy: () -> Unit, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onCopy),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        number.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(top = 2.dp)) {
                Text(
                    scan.content,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Text(
                            scan.format,
                            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        dateFmt().format(Date(scan.ts)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Rounded.Close, contentDescription = "Удалить запись",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------- Экспорт ----------

private fun exportXlsx(context: Context, scans: List<Scan>) {
    try {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "qrow_$stamp.xlsx")
        // В файле — по порядку сканирования (старые сверху)
        val rows = scans.reversed().mapIndexed { i, s ->
            listOf((i + 1).toString(), dateFmtFull().format(Date(s.ts)), s.content, s.format)
        }
        file.outputStream().use {
            XlsxWriter.write(it, listOf("№", "Дата и время", "Данные", "Тип"), rows)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Экспорт в Excel"))
    } catch (e: Exception) {
        Toast.makeText(context, "Не удалось экспортировать: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
