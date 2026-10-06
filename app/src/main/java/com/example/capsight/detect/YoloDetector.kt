package com.example.capsight.detect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.createBitmap
import com.example.capsight.TAG
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** One inference: detections in frame pixels plus timings for the debug view. */
data class DetectorOutput(
    val detections: List<Detection>,
    val preprocessMs: Long,
    val inferenceMs: Long,
    val postprocessMs: Long,
)

/**
 * YOLOv8n on LiteRT (FR-DET-01, CLAUDE.md §6.4, §7).
 *
 * Tries the GPU delegate when the device is on LiteRT's compatibility list, otherwise CPU
 * with [CPU_THREADS] threads (never NNAPI, deprecated in Android 15). The interpreter is not
 * thread-safe and a GPU delegate is bound to the thread that created it: construct, use and
 * close this object on ONE dedicated thread.
 *
 * Input layout (NCHW or NHWC) and size are read from the model, so a re-export at another
 * size or with the older TFLite exporter still works.
 */
class YoloDetector(
    context: Context,
    preferGpu: Boolean,
    modelAsset: String = MODEL_ASSET,
    labels: List<String> = CocoLabels.NAMES,
    classIdOffset: Int = 0,
) : Closeable {

    /** "GPU" or "CPU xN", for the debug overlay. */
    val backend: String

    private val interpreter: Interpreter
    private var gpu: GpuDelegate? = null
    private val inputSize: Int
    private val channelsFirst: Boolean
    private val input: ByteBuffer
    private val outputBuffer: ByteBuffer
    private val output: FloatArray
    private val decoder = YoloDecoder(labels, classIdOffset)

    // Reused every frame: letterboxed canvas and its pixels.
    private val square: Bitmap
    private val squareCanvas: Canvas
    private val pixels: IntArray
    private val floats: FloatArray
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()

    init {
        val model = loadModel(context, modelAsset)
        var created: Interpreter? = null
        var label = ""
        if (preferGpu) {
            try {
                val compat = CompatibilityList()
                if (compat.isDelegateSupportedOnThisDevice) {
                    val delegate = GpuDelegate(compat.bestOptionsForThisDevice)
                    try {
                        created = Interpreter(model, Interpreter.Options().addDelegate(delegate))
                        gpu = delegate
                        label = "GPU"
                    } catch (e: Exception) {
                        delegate.close()
                        throw e
                    }
                } else {
                    Log.i(TAG, "GPU delegate not supported on this device; using CPU")
                }
            } catch (e: Exception) {
                Log.w(TAG, "GPU delegate failed; falling back to CPU", e)
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "GPU delegate native library missing; falling back to CPU", e)
            }
        }
        if (created == null) {
            created = Interpreter(model, Interpreter.Options().setNumThreads(CPU_THREADS))
            label = "CPU x$CPU_THREADS"
        }
        interpreter = created
        backend = label

        // §7.2: verify the model I/O on first run by logging it.
        val inT = interpreter.getInputTensor(0)
        val outT = interpreter.getOutputTensor(0)
        val shape = inT.shape()
        Log.i(TAG, "model $modelAsset on $backend: input ${shape.contentToString()} ${inT.dataType()}, " +
            "output ${outT.shape().contentToString()} ${outT.dataType()}")
        require(shape.size == 4 && shape[0] == 1) { "unexpected input shape ${shape.contentToString()}" }
        channelsFirst = shape[1] == RGB
        inputSize = if (channelsFirst) shape[2] else shape[1]

        input = ByteBuffer.allocateDirect(inputSize * inputSize * RGB * FLOAT_BYTES).order(ByteOrder.nativeOrder())
        val outShape = outT.shape()
        require(outShape.size == 3 && outShape[1] == BOX_CHANNELS + labels.size) {
            "$modelAsset output ${outShape.contentToString()} does not match ${labels.size} classes"
        }
        val outCount = outShape.fold(1) { a, b -> a * b }
        outputBuffer = ByteBuffer.allocateDirect(outCount * FLOAT_BYTES).order(ByteOrder.nativeOrder())
        output = FloatArray(outCount)
        square = createBitmap(inputSize, inputSize)
        squareCanvas = Canvas(square)
        pixels = IntArray(inputSize * inputSize)
        floats = FloatArray(inputSize * inputSize * RGB)
    }

    /**
     * Detects objects in [frame].
     * @param minScore confidence threshold (FR-DET-03)
     * @param classes class ids to keep, null for all 80
     */
    fun detect(frame: Bitmap, minScore: Float, classes: Set<Int>?): DetectorOutput {
        val t0 = SystemClock.elapsedRealtime()
        val lb = Letterbox.compute(frame.width, frame.height, inputSize)
        fillInput(frame, lb)
        val t1 = SystemClock.elapsedRealtime()
        outputBuffer.rewind()
        interpreter.run(input, outputBuffer)
        val t2 = SystemClock.elapsedRealtime()
        outputBuffer.rewind()
        outputBuffer.asFloatBuffer().get(output)
        val dets = decoder.decode(output, minScore, lb, classes)
        val t3 = SystemClock.elapsedRealtime()
        return DetectorOutput(dets, t1 - t0, t2 - t1, t3 - t2)
    }

    private fun fillInput(frame: Bitmap, lb: Letterbox) {
        squareCanvas.drawColor(Color.rgb(Letterbox.PAD_GREY, Letterbox.PAD_GREY, Letterbox.PAD_GREY))
        dst.set(lb.padX, lb.padY, lb.padX + lb.scaledWidth, lb.padY + lb.scaledHeight)
        squareCanvas.drawBitmap(frame, null, dst, paint)
        square.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        val plane = inputSize * inputSize
        val f = floats
        if (channelsFirst) {
            for (i in 0 until plane) {
                val p = pixels[i]
                f[i] = ((p shr 16) and 0xFF) / MAX_CHANNEL
                f[plane + i] = ((p shr 8) and 0xFF) / MAX_CHANNEL
                f[2 * plane + i] = (p and 0xFF) / MAX_CHANNEL
            }
        } else {
            for (i in 0 until plane) {
                val p = pixels[i]
                f[3 * i] = ((p shr 16) and 0xFF) / MAX_CHANNEL
                f[3 * i + 1] = ((p shr 8) and 0xFF) / MAX_CHANNEL
                f[3 * i + 2] = (p and 0xFF) / MAX_CHANNEL
            }
        }
        input.rewind()
        input.asFloatBuffer().put(f)
    }

    override fun close() {
        interpreter.close()
        gpu?.close()
        gpu = null
    }

    companion object {
        const val MODEL_ASSET = "yolov8n_320_fp32.tflite"

        /** CLAUDE.md §6.4: CPU fallback with 4 threads. */
        private const val CPU_THREADS = 4
        private const val RGB = 3
        private const val BOX_CHANNELS = 4
        private const val FLOAT_BYTES = 4
        private const val MAX_CHANNEL = 255f

        private fun loadModel(context: Context, asset: String): MappedByteBuffer =
            context.assets.openFd(asset).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
    }
}
