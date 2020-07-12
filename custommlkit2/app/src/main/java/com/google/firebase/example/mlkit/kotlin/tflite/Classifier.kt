package com.google.firebase.example.mlkit.kotlin.tflite

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.os.Trace
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.TensorOperator
import org.tensorflow.lite.support.common.TensorProcessor
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.ResizeOp.ResizeMethod
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.support.label.TensorLabel
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.IOException
import java.nio.MappedByteBuffer
import java.util.*
import kotlin.Comparator
import kotlin.collections.ArrayList


/** A classifier specialized to label images using TensorFlow Lite.  */
abstract class Classifier constructor(activity: Activity?, device: Device?, numThreads: Int) {
    enum class Device {
        CPU, GPU
    }

    /** Number of results to show in the UI.  */
    private val MAX_RESULTS = 3

    /** The loaded TensorFlow Lite model.  */
    private var tfliteModel: MappedByteBuffer? = null

    private var imageSizeX = 0

    private var imageSizeY = 0

    private var gpuDelegate: GpuDelegate? = null

    protected var tflite: Interpreter? = null

    private val tfliteOptions: Interpreter.Options = Interpreter.Options()

    private var labels: List<String>? = null

    private var inputImageBuffer: TensorImage? = null

    private var outputProbabilityBuffer: TensorBuffer? = null

    private var probabilityProcessor: TensorProcessor? = null
    companion object{
        @Throws(IOException::class)
        fun create(activity: Activity?, device: Device?, numThreads: Int): Classifier? {
            return ClassifierFloatMobileNet(activity, device, numThreads)
        }
    }

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

    @Throws(IOException::class)
    protected open fun Classifier(activity: Activity?, device: Device?, numThreads: Int) {
        tfliteModel = FileUtil.loadMappedFile(activity!!, getModelPath()!!)
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
        labels = FileUtil.loadLabels(activity, getLabelPath()!!)

        // Reads type and shape of input and output tensors, respectively.
        val imageTensorIndex = 0
        val imageShape = tflite!!.getInputTensor(imageTensorIndex).shape() // {1, height, width, 3}
        imageSizeY = imageShape[1]
        imageSizeX = imageShape[2]
        val imageDataType: DataType = tflite!!.getInputTensor(imageTensorIndex).dataType()
        val probabilityTensorIndex = 0
        val probabilityShape = tflite!!.getOutputTensor(probabilityTensorIndex).shape() // {1, NUM_CLASSES}
        val probabilityDataType: DataType = tflite!!.getOutputTensor(probabilityTensorIndex).dataType()

        // Creates the input tensor.
        inputImageBuffer = TensorImage(imageDataType)

        // Creates the output tensor and its processor.
        outputProbabilityBuffer = TensorBuffer.createFixedSize(probabilityShape, probabilityDataType)

        // Creates the post processor for the output probability.
        probabilityProcessor = TensorProcessor.Builder().add(getPostprocessNormalizeOp()).build()

    }

    /** Runs inference and returns the classification results.  */
    open fun recognizeImage(bitmap: Bitmap, sensorOrientation: Int): List<Recognition?>? {
        // Logs this method so that it can be analyzed with systrace.
        Trace.beginSection("recognizeImage")
        Trace.beginSection("loadImage")
        val startTimeForLoadImage = SystemClock.uptimeMillis()
        inputImageBuffer = loadImage(bitmap, sensorOrientation)
        val endTimeForLoadImage = SystemClock.uptimeMillis()
        Trace.endSection()


        // Runs the inference call.
        Trace.beginSection("runInference")
        val startTimeForReference = SystemClock.uptimeMillis()
        tflite!!.run(inputImageBuffer!!.getBuffer(), outputProbabilityBuffer!!.buffer.rewind())
        val endTimeForReference = SystemClock.uptimeMillis()
        Trace.endSection()


        // Gets the map of label and probability.
        val labeledProbability = TensorLabel(labels!!, probabilityProcessor!!.process(outputProbabilityBuffer))
                .mapWithFloatValue
        Trace.endSection()

        // Gets top-k results.
        return getTopKProbability(labeledProbability)
    }

    /** Closes the interpreter and model to release resources.  */
    open fun close() {
        if (tflite != null) {
            tflite!!.close()
            tflite = null
        }
        if (gpuDelegate != null) {
            gpuDelegate!!.close()
            gpuDelegate = null
        }
        tfliteModel = null
    }

    /** Get the image size along the x axis.  */
    open fun getImageSizeX(): Int {
        return imageSizeX
    }

    /** Get the image size along the y axis.  */
    open fun getImageSizeY(): Int {
        return imageSizeY
    }

    /** Loads input image, and applies preprocessing.  */
    open fun loadImage(bitmap: Bitmap, sensorOrientation: Int): TensorImage {
        // Loads bitmap into a TensorImage.
        inputImageBuffer!!.load(bitmap)

        // Creates processor for the TensorImage.
        val cropSize = Math.min(bitmap.width, bitmap.height)
        val numRoration = sensorOrientation / 90
        val imageProcessor = ImageProcessor.Builder()
                .add(ResizeWithCropOrPadOp(cropSize, cropSize))
                .add(ResizeOp(imageSizeX, imageSizeY, ResizeMethod.NEAREST_NEIGHBOR))
                .add(Rot90Op(numRoration))
                .add(getPreprocessNormalizeOp())
                .build()
        return imageProcessor.process(inputImageBuffer)
    }

    /** Gets the top-k results.  */
    open fun getTopKProbability(labelProb: Map<String, Float>): List<Recognition?>? {
        // Find the best classifications.
        val pq: PriorityQueue<Recognition> = PriorityQueue(
                MAX_RESULTS,
                object : Comparator<Recognition?> {

                    override fun compare(p0: Recognition?, p1: Recognition?): Int {
                        return java.lang.Float.compare(p1?.confidence!!, p0?.confidence!!)
                    }
                })
        for ((key, value) in labelProb) {
            pq.add(Recognition("" + key, key, value, null))
        }
        val recognitions: ArrayList<Recognition?> = ArrayList()
        val recognitionsSize = Math.min(pq.size, MAX_RESULTS)
        for (i in 0 until recognitionsSize) {
            recognitions.add(pq.poll())
        }
        return recognitions
    }

    /** Gets the name of the model file stored in Assets.  */
    protected abstract fun getModelPath(): String?

    /** Gets the name of the label file stored in Assets.  */
    protected abstract fun getLabelPath(): String?

    /** Gets the TensorOperator to nomalize the input image in preprocessing.  */
    protected abstract fun getPreprocessNormalizeOp(): TensorOperator?

    protected abstract fun getPostprocessNormalizeOp(): TensorOperator?
}