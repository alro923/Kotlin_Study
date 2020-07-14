package com.google.firebase.example.mlkit.kotlin

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Typeface
import android.media.ImageReader.OnImageAvailableListener
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.util.TypedValue
import com.google.firebase.example.mlkit.kotlin.env.BorderedText
import com.google.firebase.example.mlkit.kotlin.tflite.Classifier
import org.tensorflow.lite.examples.classification.R
import java.io.IOException


class ClassifierActivity : CameraActivity(), OnImageAvailableListener {
    private val DESIRED_PREVIEW_SIZE = Size(640, 480)
    private val TEXT_SIZE_DIP : Float = 10F
    private var rgbFrameBitmap : Bitmap? = null
    private var lastProcessingTimeMs : Long = 0L
    private var sensorOrientation = 0
    private lateinit var classifier : Classifier
    private lateinit var borderedText: BorderedText
    private var imageSizeX = 0
    private var imageSizeY= 0

    override fun getLayoutId(): Int {
        Log.e("로그", "getLayoutId")
        return R.layout.tfe_ic_camera_connection_fragment
    }

    @SuppressLint("LongLogTag")
    override fun getDesiredPreviewFrameSize(): Size {
        Log.e("로그", "getDesiredPreviewFrameSize")
        return DESIRED_PREVIEW_SIZE
    }

    override fun onPreviewSizeChosen(size: Size, rotation: Int) {
        val textSizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, resources.displayMetrics)
        borderedText = BorderedText(textSizePx)
        borderedText.setTypeface(Typeface.MONOSPACE)

        recreateClassifier(getDevice(), getNumberThreads())
        if(classifier == null){
            return
        }

        previewWidth = size.width
        previewHeight = size.height

        sensorOrientation = rotation - getScreenOrientation()
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        Log.e("로그", "Camera orientation relative to screen canvas: %d$sensorOrientation")
        Log.e("로그", "Initializing at size %dx%d$previewWidth$previewHeight")
    }
    override fun processImage() {
        rgbFrameBitmap!!.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight)
        val cropSize = previewWidth.coerceAtMost(previewHeight)

        runInBackground(
                Runnable {
                    @Override
                    fun run() {
                        if (classifier != null) {
                            val startTime = SystemClock.uptimeMillis()
                            val results: List<Classifier.Recognition?>? = classifier.recognizeImage(rgbFrameBitmap!!, sensorOrientation)
                            lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime
                            Log.e("로그", "Detect: %s$results")

                            runOnUiThread {
                                @Override
                                fun run(){
                                    showResultsInBottomSheet(results)
                                    showFrameInfo(previewWidth.toString() + "x" + previewHeight)
                                    showCropInfo(imageSizeX.toString() + "x" + imageSizeY)
                                    showCameraResolution(cropSize.toString() + "x" + cropSize)
                                    showRotationInfo(sensorOrientation.toString())
                                    showInference(lastProcessingTimeMs.toString() + "ms")
                                }

                            }
                        }
                        readyForNextImage()
                    }
                })
    }
    @SuppressLint("LongLogTag")
    override fun onInferenceConfigurationChanged(){
        if (rgbFrameBitmap == null) {
            // Defer creation until we're getting camera frames.
            return
        }
        val device: Classifier.Device = getDevice()
        val numThreads = getNumberThreads()
        runInBackground(Runnable { recreateClassifier(device, numThreads) })

        Log.e("로그", "onInferenceConfigurationChanged")
    }

    private fun recreateClassifier(device : Classifier.Device, numThreads : Int){
        if(classifier != null){
            Log.e("로그", "Closing classifier.")
            classifier.close()
        }
        try{
            Log.e("로그", "Creating classifier (device=%s, numThreads=%d)$device$numThreads")
            classifier = Classifier.create(this, device, numThreads)!!
        }catch (e : IOException){
            Log.e("로그", "Failed to create classifier.")
        }

        // Updates the input image size.
        imageSizeX = classifier.getImageSizeX()
        imageSizeY = classifier.getImageSizeY()
        Log.e("로그", "recreateClassifier")
    }
}