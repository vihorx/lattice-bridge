package com.lattice.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

// Jedna detekcija. Sve koordinate normalizovane 0..1 u space-u ORIGINALNOG
// bitmap-a (1280x720), ne u space-u 640x640 letterbox-a. Server skalira u FOV math.
data class Detection(
    val classId: Int,
    val label: String,
    val confidence: Float,
    val nx: Float,   // bbox centar X, 0..1
    val ny: Float,   // bbox centar Y, 0..1
    val nw: Float,   // bbox sirina, 0..1
    val nh: Float    // bbox visina, 0..1
)

/**
 * YOLOv8n TFLite inference.
 *
 * Android koncepti za Python-jakog usera:
 * - Executors.newSingleThreadExecutor() = thread pool sa 1 worker thread-om.
 *   TFLite inference traje 100-500ms; ne sme na main (UI) thread.
 * - MappedByteBuffer = mmap fajla u memoriji, isto kao Python mmap.mmap().
 *   TFLite ucitava model bez kopiranja u RAM.
 * - AtomicBoolean = thread-safe boolean za busy flag. Bez ovoga bi 2 paralelne
 *   inference-a mogle da se preklope.
 */
class Yolo(context: Context) {
    private val interpreter: Interpreter
    private val labels: List<String>
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    private val inputSize = 640

    // Direct buffer = van JVM heap-a, TFLite moze pisati bez GC pressure.
    // 1 batch * 640 * 640 piksela * 3 kanala (RGB) * 4 bajta (float32)
    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(1 * inputSize * inputSize * 3 * 4)
        .order(ByteOrder.nativeOrder())

    // YOLOv8 output: [1, 84, 8400]. 84 = 4 bbox (cx,cy,w,h) + 80 class scores.
    // 8400 = ukupno anchor predikcija iz 3 scale-a feature mapa.
    private val outputBuffer = Array(1) { Array(84) { FloatArray(8400) } }

    private val confThreshold = 0.40f
    private val iouThreshold = 0.45f
    private val pixelsBuf = IntArray(inputSize * inputSize)

    init {
        val modelBuffer = loadModelFile(context, "yolov8n.tflite")
        val options = Interpreter.Options().apply {
            // Helio G99 ima 8 jezgara (2x A76 + 6x A55). 4 thread-a je dobar default.
            setNumThreads(4)
        }
        interpreter = Interpreter(modelBuffer, options)
        labels = context.assets.open("labels.txt").bufferedReader().useLines { it.toList() }
        Log.i("Yolo", "model + ${labels.size} labels loaded")
    }

    private fun loadModelFile(context: Context, name: String): MappedByteBuffer {
        val fd = context.assets.openFd(name)
        val input = FileInputStream(fd.fileDescriptor)
        return input.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    /**
     * Asinhrona detekcija. Ako prethodna inference traje, ovaj frame se TIHO PRESKACE
     * (compareAndSet). To znaci da getBitmap() na 2 Hz nikad ne ceka inference.
     * Realan inference rate moze biti manji od 2 Hz na slabijem hardveru — to je OK.
     */
    fun detectAsync(bitmap: Bitmap, callback: (List<Detection>) -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        executor.execute {
            try {
                val dets = detect(bitmap)
                callback(dets)
            } catch (t: Throwable) {
                Log.e("Yolo", "inference error: ${t.message}")
                callback(emptyList())
            } finally {
                busy.set(false)
            }
        }
    }

    private fun detect(bitmap: Bitmap): List<Detection> {
        val origW = bitmap.width
        val origH = bitmap.height

        // Letterbox: skaliraj bitmap da stane u 640x640 cuvajuci aspect, pad sa sivom
        // (114,114,114). YOLO standard; bez ovog stretch-resize unosi distorziju koja kvari mAP.
        val scale = min(inputSize.toFloat() / origW, inputSize.toFloat() / origH)
        val newW = (origW * scale).toInt()
        val newH = (origH * scale).toInt()
        val padX = (inputSize - newW) / 2
        val padY = (inputSize - newH) / 2

        val letterboxed = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterboxed)
        canvas.drawColor(Color.rgb(114, 114, 114))
        canvas.drawBitmap(
            bitmap,
            Rect(0, 0, origW, origH),
            Rect(padX, padY, padX + newW, padY + newH),
            null
        )

        // Bitmap -> ByteBuffer (float 0..1, RGB channels-last)
        inputBuffer.rewind()
        letterboxed.getPixels(pixelsBuf, 0, inputSize, 0, 0, inputSize, inputSize)
        for (px in pixelsBuf) {
            inputBuffer.putFloat(((px shr 16) and 0xFF) / 255f)  // R
            inputBuffer.putFloat(((px shr 8) and 0xFF) / 255f)   // G
            inputBuffer.putFloat((px and 0xFF) / 255f)           // B
        }

        interpreter.run(inputBuffer, outputBuffer)
        return postprocess(outputBuffer[0], origW, origH, scale, padX, padY)
    }

    private fun postprocess(
        output: Array<FloatArray>,  // [84][8400]
        origW: Int, origH: Int,
        scale: Float, padX: Int, padY: Int
    ): List<Detection> {
        val candidates = mutableListOf<Detection>()
        for (i in 0 until 8400) {
            var maxConf = 0f
            var maxClass = 0
            for (c in 0 until 80) {
                val s = output[4 + c][i]
                if (s > maxConf) {
                    maxConf = s
                    maxClass = c
                }
            }
            if (maxConf < confThreshold) continue

            // YOLOv8 (onnx2tf export) izlaz: cx, cy, w, h su u NORMALIZED 0..1 space.
            // (Standardni Ultralytics PyTorch export daje pixel 0..640; onnx2tf normalizuje.)
            // Validirano empirijski: sve detekcije davale @0,-38 sto je konzistentno
            // sa interpretacijom 0..1 kroz unpad formulu za pixel space.
            val cxLbPx = output[0][i] * inputSize
            val cyLbPx = output[1][i] * inputSize
            val wLbPx = output[2][i] * inputSize
            val hLbPx = output[3][i] * inputSize

            // Unpad pa unscale natrag u original bitmap (1280x720) piksel space
            val cxOrig = (cxLbPx - padX) / scale
            val cyOrig = (cyLbPx - padY) / scale
            val wOrig = wLbPx / scale
            val hOrig = hLbPx / scale

            candidates.add(Detection(
                classId = maxClass,
                label = labels.getOrNull(maxClass) ?: "?",
                confidence = maxConf,
                nx = cxOrig / origW,
                ny = cyOrig / origH,
                nw = wOrig / origW,
                nh = hOrig / origH
            ))
        }
        return nms(candidates)
    }

    private fun nms(dets: List<Detection>): List<Detection> {
        val sorted = dets.sortedByDescending { it.confidence }.toMutableList()
        val keep = mutableListOf<Detection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            keep.add(best)
            sorted.removeAll { iou(best, it) > iouThreshold }
        }
        return keep
    }

    private fun iou(a: Detection, b: Detection): Float {
        val ax1 = a.nx - a.nw / 2f; val ax2 = a.nx + a.nw / 2f
        val ay1 = a.ny - a.nh / 2f; val ay2 = a.ny + a.nh / 2f
        val bx1 = b.nx - b.nw / 2f; val bx2 = b.nx + b.nw / 2f
        val by1 = b.ny - b.nh / 2f; val by2 = b.ny + b.nh / 2f
        val iw = max(0f, min(ax2, bx2) - max(ax1, bx1))
        val ih = max(0f, min(ay2, by2) - max(ay1, by1))
        val intersect = iw * ih
        val unionA = (ax2 - ax1) * (ay2 - ay1)
        val unionB = (bx2 - bx1) * (by2 - by1)
        val union = unionA + unionB - intersect
        return if (union > 0f) intersect / union else 0f
    }

    fun close() {
        executor.shutdown()
        interpreter.close()
    }
}
