package com.example.capsight.depth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.createBitmap
import com.example.capsight.TAG
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** One depth inference: the relative inverse depth map (row-major) and timings. */
class DepthOutput(val map: FloatArray, val width: Int, val height: Int, val preprocessMs: Long, val inferenceMs: Long)

/**
 * MiDaS v2.1 small on LiteRT (FR-OBS-04, ml/depth/README.md). Input [1, 256, 256, 3] float32 RGB
 * NHWC, ImageNet-normalised; output [1, 256, 256, 1] float32 relative INVERSE depth (bigger = nearer).
 * The shapes are checked on load and logged; anything else throws, and the caller turns depth off.
 *
 * Same backend rules as YoloDetector (CLAUDE.md §6.4): GPU delegate when the device is on LiteRT's
 * compatibility list, otherwise CPU with [CPU_THREADS] threads, never NNAPI. Not thread-safe and a GPU
 * delegate is bound to its creating thread: construct, use and close on ONE dedicated thread.
 */
class MidasDepth(context: Context, preferGpu: Boolean, modelAsset: String = MODEL_ASSET) : Closeable {

    /** "GPU" or "CPU xN", for the debug view. */
    val backend: String

    private val interpreter: Interpreter
    private var gpu: GpuDelegate? = null
    private val size: Int
    private val input: ByteBuffer
    private val outputBuffer: ByteBuffer
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
                    Log.i(TAG, "depth: GPU delegate not supported on this device; using CPU")
                }
            } catch (e: Exception) {
                Log.w(TAG, "depth: GPU delegate failed; falling back to CPU", e)
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "depth: GPU delegate native library missing; falling back to CPU", e)
            }
        }
        if (created == null) {
            created = Interpreter(model, Interpreter.Options().setNumThreads(CPU_THREADS))
            label = "CPU x$CPU_THREADS"
        }
        interpreter = created
        backend = label

        try {
            val inT = interpreter.getInputTensor(0)
            val outT = interpreter.getOutputTensor(0)
            val inShape = inT.shape()
            val outShape = outT.shape()
            Log.i(TAG, "model $modelAsset on $backend: input ${inShape.contentToString()} ${inT.dataType()}, " +
                "output ${outShape.contentToString()} ${outT.dataType()}")
            require(inShape.size == 4 && inShape[0] == 1 && inShape[3] == RGB && inShape[1] == inShape[2]) {
                "unexpected input ${inShape.contentToString()}, want [1, N, N, 3]"
            }
            size = inShape[1]
            val outOk = outShape.size in 3..4 && outShape[0] == 1 && outShape[1] == size && outShape[2] == size &&
                (outShape.size == 3 || outShape[3] == 1)
            require(outOk) { "unexpected output ${outShape.contentToString()}, want [1, $size, $size, 1]" }
            require(inT.dataType() == DataType.FLOAT32 && outT.dataType() == DataType.FLOAT32) {
                "want float32 I/O, got ${inT.dataType()} -> ${outT.dataType()}"
            }
        } catch (e: Exception) {
            close()
            throw e
        }
        input = ByteBuffer.allocateDirect(size * size * RGB * FLOAT_BYTES).order(ByteOrder.nativeOrder())
        outputBuffer = ByteBuffer.allocateDirect(size * size * FLOAT_BYTES).order(ByteOrder.nativeOrder())
        square = createBitmap(size, size)
        squareCanvas = Canvas(square)
        pixels = IntArray(size * size)
        floats = FloatArray(size * size * RGB)
    }

    /** Depth of an upright [frame], resized (not letterboxed) to the model size like the reference script. */
    fun estimate(frame: Bitmap): DepthOutput {
        val t0 = SystemClock.elapsedRealtime()
        dst.set(0, 0, size, size)
        squareCanvas.drawBitmap(frame, null, dst, paint)
        square.getPixels(pixels, 0, size, 0, 0, size, size)
        val f = floats
        for (i in pixels.indices) {
            val p = pixels[i]
            f[RGB * i] = (((p shr 16) and 0xFF) / MAX_CHANNEL - MEAN_R) / STD_R
            f[RGB * i + 1] = (((p shr 8) and 0xFF) / MAX_CHANNEL - MEAN_G) / STD_G
            f[RGB * i + 2] = ((p and 0xFF) / MAX_CHANNEL - MEAN_B) / STD_B
        }
        input.rewind()
        input.asFloatBuffer().put(f)
        val t1 = SystemClock.elapsedRealtime()
        outputBuffer.rewind()
        interpreter.run(input, outputBuffer)
        val t2 = SystemClock.elapsedRealtime()
        outputBuffer.rewind()
        val map = FloatArray(size * size)
        outputBuffer.asFloatBuffer().get(map)
        return DepthOutput(map, size, size, t1 - t0, t2 - t1)
    }

    override fun close() {
        interpreter.close()
        gpu?.close()
        gpu = null
    }

    companion object {
        const val MODEL_ASSET = "midas_small_256_fp32.tflite"

        /** CLAUDE.md §6.4: CPU fallback with 4 threads. */
        private const val CPU_THREADS = 4
        private const val RGB = 3
        private const val FLOAT_BYTES = 4
        private const val MAX_CHANNEL = 255f

        // ImageNet normalisation (ml/depth/README.md).
        private const val MEAN_R = 0.485f
        private const val MEAN_G = 0.456f
        private const val MEAN_B = 0.406f
        private const val STD_R = 0.229f
        private const val STD_G = 0.224f
        private const val STD_B = 0.225f

        private fun loadModel(context: Context, asset: String): MappedByteBuffer =
            context.assets.openFd(asset).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
    }
}
