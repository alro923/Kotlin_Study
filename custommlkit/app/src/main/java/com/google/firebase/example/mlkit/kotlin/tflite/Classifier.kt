package com.google.firebase.example.mlkit.kotlin.tflite

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.os.Trace
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.TensorOperator
import org.tensorflow.lite.support.common.TensorProcessor
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.support.label.TensorLabel
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.IOException
import java.nio.MappedByteBuffer
import java.util.*
import java.util.logging.Logger

/** A classifier specialized to label images using TensorFlow Lite.  */
abstract class Classifier constructor(activity: Activity?, device: Device?, numThreads: Int) {
    /** The runtime device type used for executing classification.  */
    enum class Device {
        CPU, GPU
    }

    /** The loaded TensorFlow Lite model.  */
    private var tfliteModel: MappedByteBuffer?

    /** Get the image size along the x axis.  */
    /** Image size along the x axis.  */
    val imageSizeX: Int

    /** Get the image size along the y axis.  */
    /** Image size along the y axis.  */
    val imageSizeY: Int

    /** Optional GPU delegate for acceleration.  */
    private var gpuDelegate: GpuDelegate? = null

    /** An instance of the driver class to run model inference with Tensorflow Lite.  */
    protected var tflite: Interpreter?

    /** Options for configuring the Interpreter.  */
    private val tfliteOptions = Interpreter.Options()

    /** Labels corresponding to the output of the vision model.  */
    private val labels: List<String>

    /** Input image TensorBuffer.  */
    private var inputImageBuffer: TensorImage

    /** Output probability TensorBuffer.  */
    private val outputProbabilityBuffer: TensorBuffer

    /** Processer to apply post processing of the output probability.  */
    private val probabilityProcessor: TensorProcessor

    /** An immutable result returned by a Classifier describing what was recognized.  */
    class Recognition(
            /**
             * A unique identifier for what has been recognized. Specific to the class, not the instance of
             * the object.
             */
            val id: String?,
            /** Display name for the recognition.  */
            val title: String?,
            /**
             * A sortable score for how good the recognition is relative to others. Higher should be better.
             */
            val confidence: Float?,
            /** Optional location within the source image for the location of the recognized object.  */
            private var location: RectF?) {

        fun getLocation(): RectF {
            return RectF(location)
        }

        fun setLocation(location: RectF?) {
            this.location = location
        }

        override fun toString(): String {
            var resultString = ""
            if (id != null) {
                resultString += "[$id] "
            }
            if (title != null) {
                resultString += "$title "
            }
            if (confidence != null) {
                resultString += String.format("(%.1f%%) ", confidence * 100.0f)
            }
            if (location != null) {
                resultString += location.toString() + " "
            }
            return resultString.trim { it <= ' ' }
        }

    }

    /** Runs inference and returns the classification results.  */
    fun recognizeImage(bitmap: Bitmap, sensorOrientation: Int): List<Recognition> {
        // Logs this method so that it can be analyzed with systrace.
        Trace.beginSection("recognizeImage")
        Trace.beginSection("loadImage")
        val startTimeForLoadImage = SystemClock.uptimeMillis()
        inputImageBuffer = loadImage(bitmap, sensorOrientation)
        val endTimeForLoadImage = SystemClock.uptimeMillis()
        Trace.endSection()
        // LOGGER.v("Timecost to load the image: " + (endTimeForLoadImage - startTimeForLoadImage))

        // Runs the inference call.
        Trace.beginSection("runInference")
        val startTimeForReference = SystemClock.uptimeMillis()
        tflite!!.run(inputImageBuffer.getBuffer(), outputProbabilityBuffer.getBuffer().rewind())
        val endTimeForReference = SystemClock.uptimeMillis()
        Trace.endSection()
        // LOGGER.v("Timecost to run model inference: " + (endTimeForReference - startTimeForReference))

        // Gets the map of label and probability.
        val labeledProbability: Map<String, Float> = TensorLabel(labels, probabilityProcessor.process(outputProbabilityBuffer))
                .getMapWithFloatValue()
        Trace.endSection()

        // Gets top-k results.
        return getTopKProbability(labeledProbability)
    }

    /** Closes the interpreter and model to release resources.  */
    fun close() {
        if (tflite != null) {
            tflite!!.close()
            tflite = null
        }
//        if (gpuDelegate != null) {
//            gpuDelegate.close()
//            gpuDelegate = null
//        }
        tfliteModel = null
    }

    /** Loads input image, and applies preprocessing.  */
    private fun loadImage(bitmap: Bitmap, sensorOrientation: Int): TensorImage {
        // Loads bitmap into a TensorImage.
        inputImageBuffer.load(bitmap)

        // Creates processor for the TensorImage.
        val cropSize = Math.min(bitmap.width, bitmap.height)
        val numRoration = sensorOrientation / 90
        val imageProcessor: ImageProcessor = ImageProcessor.Builder()
                .add(ResizeWithCropOrPadOp(cropSize, cropSize))
                .add(ResizeOp(imageSizeX, imageSizeY, ResizeOp.ResizeMethod.NEAREST_NEIGHBOR))
                .add(Rot90Op(numRoration))
                .add(preprocessNormalizeOp)
                .build()
        return imageProcessor.process(inputImageBuffer)
    }

    /** Gets the name of the model file stored in Assets.  */
    protected abstract val modelPath: String?

    /** Gets the name of the label file stored in Assets.  */
    protected abstract val labelPath: String?

    /** Gets the TensorOperator to nomalize the input image in preprocessing.  */
    protected abstract val preprocessNormalizeOp: TensorOperator?

    /**
     * Gets the TensorOperator to dequantize the output probability in post processing.
     *
     *
     * For quantized model, we need de-quantize the prediction with NormalizeOp (as they are all
     * essentially linear transformation). For float model, de-quantize is not required. But to
     * uniform the API, de-quantize is added to float model too. Mean and std are set to 0.0f and
     * 1.0f, respectively.
     */
    protected abstract val postprocessNormalizeOp: TensorOperator?

    companion object {
        // private val LOGGER: Logger = Logger()

        /** Number of results to show in the UI.  */
        private const val MAX_RESULTS = 3

        /**
         * Creates a classifier with the provided configuration.
         *
         * @param activity The current Activity.
         * @param device The device to use for classification.
         * @param numThreads The number of threads to use for classification.
         * @return A classifier with the desired configuration.
         */
        @Throws(IOException::class)
        fun create(activity: Activity?, device: Device?, numThreads: Int): Classifier {
            return ClassifierFloatMobileNet(activity, device, numThreads)
        }

        /** Gets the top-k results.  */
        private fun getTopKProbability(labelProb: Map<String, Float>): List<Recognition> {
            // Find the best classifications.
            val pq = PriorityQueue(
                    MAX_RESULTS,
                    object : Comparator<Recognition?> {
                        override fun compare(lhs: Recognition?, rhs: Recognition?): Int {
                            // Intentionally reversed to put high confidence at the head of the queue.
                            return java.lang.Float.compare(rhs?.confidence!!, lhs?.confidence!!)
                        }
                    })
            for ((key, value) in labelProb) {
                pq.add(Recognition("" + key, key, value, null))
            }
            val recognitions = ArrayList<Recognition>()
            val recognitionsSize = Math.min(pq.size, MAX_RESULTS)
            for (i in 0 until recognitionsSize) {
                pq.poll()?.let { recognitions.add(it) }
            }
            return recognitions
        }
    }

    /** Initializes a `Classifier`.  */
    init {
        tfliteModel = activity?.let { modelPath?.let { it1 -> FileUtil.loadMappedFile(it, it1) } }
        when (device) {
            Device.GPU -> {
                gpuDelegate = GpuDelegate()
                tfliteOptions.addDelegate(gpuDelegate)
            }
            Device.CPU -> {
            }
        }
        tfliteOptions.setNumThreads(numThreads)
        tflite = Interpreter(tfliteModel!!, tfliteOptions)

        // Loads labels out from the label file.
        labels = activity?.let { labelPath?.let { it1 -> FileUtil.loadLabels(it, it1) } } as List<String>

        // Reads type and shape of input and output tensors, respectively.
        val imageTensorIndex = 0
        val imageShape = tflite!!.getInputTensor(imageTensorIndex).shape() // {1, height, width, 3}
        imageSizeY = imageShape[1]
        imageSizeX = imageShape[2]
        val imageDataType = tflite!!.getInputTensor(imageTensorIndex).dataType()
        val probabilityTensorIndex = 0
        val probabilityShape = tflite!!.getOutputTensor(probabilityTensorIndex).shape() // {1, NUM_CLASSES}
        val probabilityDataType = tflite!!.getOutputTensor(probabilityTensorIndex).dataType()

        // Creates the input tensor.
        inputImageBuffer = TensorImage(imageDataType)

        // Creates the output tensor and its processor.
        outputProbabilityBuffer = TensorBuffer.createFixedSize(probabilityShape, probabilityDataType)

        // Creates the post processor for the output probability.
        probabilityProcessor = TensorProcessor.Builder().add(postprocessNormalizeOp).build()
        // LOGGER.d("Created a Tensorflow Lite Image Classifier.")
    }
}