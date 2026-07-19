package com.example.blindassist

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.blindassist.ui.theme.BlindAssistTheme
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.Executors

val COCO_LABELS = listOf(
    "person","bicycle","car","motorcycle","airplane","bus","train","truck","boat",
    "traffic light","fire hydrant","street sign","stop sign","parking meter","bench",
    "bird","cat","dog","horse","sheep","cow","elephant","bear","zebra","giraffe","hat",
    "backpack","umbrella","shoe","eye glasses","handbag","tie","suitcase","frisbee",
    "skis","snowboard","sports ball","kite","baseball bat","baseball glove","skateboard",
    "surfboard","tennis racket","bottle","plate","wine glass","cup","fork","knife","spoon",
    "bowl","banana","apple","sandwich","orange","broccoli","carrot","hot dog","pizza",
    "donut","cake","chair","couch","potted plant","bed","mirror","dining table","window",
    "desk","toilet","door","tv","laptop","mouse","remote","keyboard","cell phone",
    "microwave","oven","toaster","sink","refrigerator","blender","book","clock","vase",
    "scissors","teddy bear","hair drier","toothbrush"
)

val OBJECT_REAL_HEIGHTS_METERS = mapOf(
    "person" to 1.7f, "bicycle" to 1.1f, "car" to 1.5f, "motorcycle" to 1.3f,
    "bus" to 3.2f, "truck" to 3.0f, "bench" to 0.9f, "chair" to 0.9f,
    "couch" to 0.85f, "bed" to 0.6f, "dining table" to 0.75f, "tv" to 0.6f,
    "dog" to 0.5f, "cat" to 0.3f, "horse" to 1.6f, "cow" to 1.4f,
    "door" to 2.0f, "refrigerator" to 1.7f, "suitcase" to 0.6f
)
const val ASSUMED_VERTICAL_FOV_DEGREES = 55f

fun estimateDistanceMeters(label: String, boxTop: Float, boxBottom: Float): Float? {
    val realHeight = OBJECT_REAL_HEIGHTS_METERS[label] ?: return null
    val boxHeightFraction = (boxBottom - boxTop).coerceIn(0.01f, 1f)
    val vfovRadians = Math.toRadians(ASSUMED_VERTICAL_FOV_DEGREES.toDouble())
    val angularHeight = boxHeightFraction * vfovRadians
    val distance = realHeight / (2f * kotlin.math.tan(angularHeight / 2f).toFloat())
    return distance
}

data class Detection(val label: String, val centerX: Float, val top: Float, val bottom: Float)

class MainActivity : ComponentActivity() {

    private var tts: TextToSpeech? = null
    private val hasCameraPermission = mutableStateOf(false)
    private val latestRecognizedText = mutableStateOf("")

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasCameraPermission.value = granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(0.9f)
                tts?.setPitch(1.0f)
            }
        }

        hasCameraPermission.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission.value) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            BlindAssistTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (hasCameraPermission.value) {
                        CameraScreen(
                            modifier = Modifier.padding(innerPadding),
                            latestRecognizedText = latestRecognizedText,
                            onSpeak = { text, _ -> tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null) }
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(innerPadding),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Camera permission is required")
                        }
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val text = latestRecognizedText.value
            tts?.speak(
                if (text.isNotBlank()) text else "No text detected yet",
                TextToSpeech.QUEUE_FLUSH, null, null
            )
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
fun CameraScreen(
    modifier: Modifier = Modifier,
    latestRecognizedText: MutableState<String>,
    onSpeak: (String, Boolean) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val textRecognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val litertEnv = remember { Environment.create() }

    val objectModel = remember {
        CompiledModel.create(context.assets, "model.tflite", CompiledModel.Options(Accelerator.CPU), litertEnv)
    }
    val depthModel = remember {
        CompiledModel.create(context.assets, "depth_model.tflite", CompiledModel.Options(Accelerator.CPU), litertEnv)
    }

    val objInputBuffers = remember { objectModel.createInputBuffers() }
    val objOutputBuffers = remember { objectModel.createOutputBuffers() }
    val depthInputBuffers = remember { depthModel.createInputBuffers() }
    val depthOutputBuffers = remember { depthModel.createOutputBuffers() }

    var lastSpokenDescription by remember { mutableStateOf("") }
    var lastSpeakTime by remember { mutableStateOf(0L) }
    var lastObstacleWarningTime by remember { mutableStateOf(0L) }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy: ImageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            val inputImage = InputImage.fromMediaImage(
                                mediaImage, imageProxy.imageInfo.rotationDegrees
                            )
                            textRecognizer.process(inputImage)
                                .addOnSuccessListener { visionText ->
                                    if (visionText.text.isNotBlank()) {
                                        latestRecognizedText.value = visionText.text
                                    }
                                }

                            try {
                                val bitmap = imageProxy.toBitmap()
                                val rotated = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)

                                // --- Object detection ---
                                val objInputSize = 320
                                val resizedForObj = Bitmap.createScaledBitmap(rotated, objInputSize, objInputSize, true)
                                val objBytes = bitmapToUint8Array(resizedForObj)
                                objInputBuffers[0].writeInt8(objBytes)

                                objectModel.run(objInputBuffers, objOutputBuffers)

                                val locations = objOutputBuffers[0].readFloat()
                                val classesArr = objOutputBuffers[1].readFloat()
                                val scoresArr = objOutputBuffers[2].readFloat()
                                val numDetectionsArr = objOutputBuffers[3].readFloat()

                                val detections = mutableListOf<Detection>()
                                val count = (numDetectionsArr.getOrNull(0) ?: 0f).toInt().coerceIn(0, 25)
                                for (i in 0 until count) {
                                    val score = scoresArr.getOrNull(i) ?: 0f
                                    if (score >= 0.5f) {
                                        val classIndex = (classesArr.getOrNull(i) ?: 0f).toInt()
                                        val label = COCO_LABELS.getOrNull(classIndex) ?: "an object"
                                        val top = locations.getOrNull(i * 4) ?: 0f
                                        val left = locations.getOrNull(i * 4 + 1) ?: 0f
                                        val bottom = locations.getOrNull(i * 4 + 2) ?: 0f
                                        val right = locations.getOrNull(i * 4 + 3) ?: 0f
                                        val centerX = (left + right) / 2f
                                        detections.add(Detection(label, centerX, top, bottom))
                                    }
                                }

                                if (detections.isNotEmpty()) {
                                    val descriptions = detections.map { d ->
                                        val position = when {
                                            d.centerX < 0.33f -> "on your left"
                                            d.centerX > 0.66f -> "on your right"
                                            else -> "ahead"
                                        }
                                        val distance = estimateDistanceMeters(d.label, d.top, d.bottom)
                                        if (distance != null) {
                                            val roundedDistance = (distance / 5).toInt() * 5
                                            if (roundedDistance < 5) {
                                                "${d.label} $position, very close"
                                            } else {
                                                "${d.label} $position, about $roundedDistance meters"
                                            }
                                        } else {
                                            "${d.label} $position"
                                        }
                                    }
                                    val description = descriptions.joinToString(", ")

                                    val now = System.currentTimeMillis()
                                    if (description != lastSpokenDescription && now - lastSpeakTime > 3000) {
                                        lastSpokenDescription = description
                                        lastSpeakTime = now
                                        onSpeak(description, false)
                                    }
                                }

                                // --- Depth estimation & obstacle warning ---
                                val depthInputSize = 256
                                val resizedForDepth = Bitmap.createScaledBitmap(rotated, depthInputSize, depthInputSize, true)
                                val depthFloats = bitmapToFloatArray(resizedForDepth)
                                depthInputBuffers[0].writeFloat(depthFloats)

                                depthModel.run(depthInputBuffers, depthOutputBuffers)

                                val depthOutput = depthOutputBuffers[0].readFloat()

                                val regionStartY = (depthInputSize * 0.6).toInt()
                                val regionEndY = depthInputSize
                                val regionStartX = (depthInputSize * 0.35).toInt()
                                val regionEndX = (depthInputSize * 0.65).toInt()

                                var sum = 0f
                                var regionCount = 0
                                var minVal = Float.MAX_VALUE
                                var maxVal = Float.MIN_VALUE
                                for (y in 0 until depthInputSize) {
                                    for (x in 0 until depthInputSize) {
                                        val v = depthOutput.getOrNull(y * depthInputSize + x) ?: 0f
                                        if (v < minVal) minVal = v
                                        if (v > maxVal) maxVal = v
                                        if (y in regionStartY until regionEndY && x in regionStartX until regionEndX) {
                                            sum += v
                                            regionCount++
                                        }
                                    }
                                }
                                val avgDepthValue = if (regionCount > 0) sum / regionCount else 0f
                                val range = (maxVal - minVal).coerceAtLeast(0.0001f)
                                val normalizedCloseness = (avgDepthValue - minVal) / range

                                val obstacleThreshold = 0.75f
                                val nowObstacle = System.currentTimeMillis()
                                if (normalizedCloseness > obstacleThreshold &&
                                    nowObstacle - lastObstacleWarningTime > 2000
                                ) {
                                    lastObstacleWarningTime = nowObstacle
                                    onSpeak("Obstacle close ahead", true)
                                }

                            } catch (e: Exception) {
                                // Ignore frame errors, keep going
                            } finally {
                                imageProxy.close()
                            }
                        } else {
                            imageProxy.close()
                        }
                    }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, cameraSelector, preview, imageAnalysis
                    )
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Auto-narrating | Obstacle warnings active | Volume Up: Read Text")
        }
    }
}

fun bitmapToUint8Array(bitmap: Bitmap): ByteArray {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val bytes = ByteArray(width * height * 3)
    var idx = 0
    for (pixel in pixels) {
        bytes[idx++] = ((pixel shr 16) and 0xFF).toByte()
        bytes[idx++] = ((pixel shr 8) and 0xFF).toByte()
        bytes[idx++] = (pixel and 0xFF).toByte()
    }
    return bytes
}

fun bitmapToFloatArray(bitmap: Bitmap): FloatArray {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val floats = FloatArray(width * height * 3)
    var idx = 0
    for (pixel in pixels) {
        floats[idx++] = ((pixel shr 16) and 0xFF) / 255.0f
        floats[idx++] = ((pixel shr 8) and 0xFF) / 255.0f
        floats[idx++] = (pixel and 0xFF) / 255.0f
    }
    return floats
}

fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bitmap
    val matrix = android.graphics.Matrix()
    matrix.postRotate(degrees.toFloat())
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

fun ImageProxy.toBitmap(): Bitmap {
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer
    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)
    val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
    val out = java.io.ByteArrayOutputStream()
    yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 100, out)
    val imageBytes = out.toByteArray()
    return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}