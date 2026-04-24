package com.example.wasuremono_prj

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.wasuremono_prj.ui.theme.Wasuremono_prjTheme
import org.tensorflow.lite.Interpreter
import java.util.concurrent.Executors
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.metadata.MetadataExtractor


class MainActivity : ComponentActivity() {

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()

    data class Detection(
        val label: String,
        val score: Float,
        val box: FloatArray
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) recreate()
        else Log.e("LiteRT", "Camera permission denied")
    }

    companion object {
        private const val MODEL_PATH = "ssdmobilenetv3.tflite"
        private const val LABEL_PATH = "labelmap.txt"
        private const val CONFIDENCE_THRESHOLD = 0.5f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkCameraPermission()
        initLiteRT()

        setContent {
            Wasuremono_prjTheme()  {
                val context = LocalContext.current
                val hasPermission = remember {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                }

                if (hasPermission) DetectorScreen()
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("カメラ権限が必要です")
                }
            }
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun initLiteRT() {
        try {
            val model = FileUtil.loadMappedFile(this, MODEL_PATH)

            val options = Interpreter.Options().apply {
                useXNNPACK=true
                setNumThreads(4)

            }
            interpreter = Interpreter(model, options)
            val extractor = MetadataExtractor(model)

            // metadataにlabelがあるか確認
            try {
                labels = this.assets.open(LABEL_PATH).bufferedReader().readLines()
                Log.d("LiteRT", "Loaded labels from assets: ${labels.size}")
            } catch (e: Exception) {
                Log.e("LiteRT", "Failed to load labels from assets: ${e.message}")
            }

            // 2. もし assets になくて、メタデータにある場合に備えたバックアップ（現在の処理）
            if (labels.isEmpty()) {
                val extractor = MetadataExtractor(model)
                val files = extractor.associatedFileNames
                val labelFileName = if (MODEL_PATH.contains("v1")) "labelmap.txt" else "labelmap.txt"

                if (files.contains(labelFileName)) {
                    val inputStream = extractor.getAssociatedFile(labelFileName)
                    labels = inputStream.bufferedReader().readLines()
                    Log.d("LiteRT", "Loaded labels from metadata: ${labels.size}")
                }
            }


            Log.d("LiteRT", "labels size = ${labels.size}")

        } catch (e: Exception) {
            Log.e("LiteRT", "Init failed: ${e.message}")
        }
    }

    @Composable
    fun DetectorScreen() {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val executor = remember { Executors.newSingleThreadExecutor() }

        val detections = remember { mutableStateListOf<Detection>() }
        var fps by remember { mutableStateOf(0f) }

        val previewView = remember { PreviewView(context) }

        LaunchedEffect(Unit) {
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            var lastTime = System.currentTimeMillis()

            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                val bitmap = imageProxy.toBitmap()
                val interp = interpreter


                if (bitmap != null && interp != null) {
                    val targetSize = if (MODEL_PATH == "ssdmobilenetv1.tflite") 300 else 320
                    val processor = ImageProcessor.Builder().add(ResizeOp(targetSize, targetSize, ResizeOp.ResizeMethod.BILINEAR))
                        .build()

                    var tensor = TensorImage(interp.getInputTensor(0).dataType())
                    tensor.load(bitmap)
                    tensor = processor.process(tensor)

                    val locations = Array(1) { Array(100) { FloatArray(4) } }
                    val classes = Array(1) { FloatArray(100) }
                    val scores = Array(1) { FloatArray(100) }
                    val num = FloatArray(1)

                    val outputs = mapOf(
                        0 to locations,
                        1 to classes,
                        2 to scores,
                        3 to num
                    )

                    interp.runForMultipleInputsOutputs(
                        arrayOf(tensor.buffer),
                        outputs
                    )

                    val result = mutableListOf<Detection>()
                    val count = num[0].toInt()

                    for (i in 0 until count) {
                        val score = scores[0][i]
                        if (score > CONFIDENCE_THRESHOLD) {
                            val label = labels.getOrNull(classes[0][i].toInt()) ?: "Unknown"
                            result.add(Detection(label, score, locations[0][i]))
                        }
                        val classIndex = classes[0][i].toInt()
                        Log.d("LiteRT", "ClassIndex: $classIndex, LabelsSize: ${labels.size}")
                    }

                    val now = System.currentTimeMillis()
                    val currentFps = 1000f / (now - lastTime)
                    lastTime = now

                    ContextCompat.getMainExecutor(context).execute {
                        detections.clear()
                        detections.addAll(result)
                        fps = currentFps
                    }

                }
                imageProxy.close()
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis
            )
        }

        Box(Modifier.fillMaxSize()) {

            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                val previewWidth = size.width
                val previewHeight = size.height
                val modelInputSize = if (MODEL_PATH == "ssdmobilenetv1.tflite") 300f else 320f

                // スケール計算（回転を考慮し、縦横の対応を逆転させる）
                val scaleX = previewWidth / modelInputSize
                val scaleY = previewHeight / modelInputSize
                // CenterCrop用のスケール
                val scale = maxOf(scaleX, scaleY)

                val offsetX = (previewWidth - modelInputSize * scale) / 2f
                val offsetY = (previewHeight - modelInputSize * scale) / 2f

                detections.forEach { detection ->
                    val box = detection.box

                    // --- 90度回転の座標変換 ---
                    // 通常のSSD出力: box[0]=top, box[1]=left, box[2]=bottom, box[3]=right
                    // これを縦画面（90度回転）に合わせると：
                    // 新しいLeft   = (1.0 - bottom)
                    // 新しいTop    = left
                    // 新しいRight  = (1.0 - top)
                    // 新しいBottom = right

                    val left = (1.0f - box[2]) * modelInputSize * scale + offsetX
                    val top = box[1] * modelInputSize * scale + offsetY
                    val right = (1.0f - box[0]) * modelInputSize * scale + offsetX
                    val bottom = box[3] * modelInputSize * scale + offsetY

                    drawRect(
                        color = Color.Red,
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                        style = Stroke(width = 8f)
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text("FPS: %.1f".format(fps), color = Color.Yellow)

            detections.forEach {
                Text(
                    "${it.label}: %.2f".format(it.score),
                    color = Color.Green
                )
            }
        }
    }
}