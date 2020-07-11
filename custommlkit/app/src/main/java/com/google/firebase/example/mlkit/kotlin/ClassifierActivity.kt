package com.google.firebase.example.mlkit.kotlin

import android.graphics.Bitmap
import android.graphics.Typeface
import android.media.ImageReader.OnImageAvailableListener
import android.os.SystemClock
import android.util.Size
import android.util.TypedValue
import android.view.InputDevice
import android.view.InputDevice.getDevice
import com.google.firebase.example.mlkit.kotlin.env.BorderedText
import com.google.firebase.example.mlkit.kotlin.tflite.Classifier
import devrel.firebase.google.com.firebaseoptions.R
import java.io.IOException
import java.util.logging.Logger

class ClassifierActivity : OnImageAvailableListener {
    private var rgbFrameBitmap: Bitmap? = null
    private var lastProcessingTimeMs: Long = 0
    private var sensorOrientation: Int? = null
    private var classifier: Classifier? = null
    private var borderedText: BorderedText? = null

    /** Input image size of the model along x axis.  */
    private var imageSizeX = 0

    /** Input image size of the model along y axis.  */
    private var imageSizeY = 0
    protected val layoutId: Int
        protected get() = R.layout.tfe_ic_camera_connection_fragment

    fun onPreviewSizeChosen(size: Size, rotation: Int) {
        val textSizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics())
        borderedText = BorderedText(textSizePx)
        borderedText!!.setTypeface(Typeface.MONOSPACE)
        recreateClassifier(getDevice(), getNumThreads())
        if (classifier == null) {
            // LOGGER.e("No classifier on preview!")
            return
        }
        previewWidth = size.width
        previewHeight = size.height
        sensorOrientation = rotation - getScreenOrientation()
        // LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation)
        // LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight)
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
    }

    protected fun processImage() {
        rgbFrameBitmap!!.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight)
        val cropSize: Int = Math.min(previewWidth, previewHeight)
        runInBackground(
                Runnable {
                    if (classifier != null) {
                        val startTime = SystemClock.uptimeMillis()
                        val results: List<Recognition> = classifier.recognizeImage(rgbFrameBitmap, sensorOrientation)
                        lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime
                        // LOGGER.v("Detect: %s", results)
                        runOnUiThread(
                                Runnable {
                                    showResultsInBottomSheet(results)
                                    showFrameInfo(previewWidth.toString() + "x" + previewHeight)
                                    showCropInfo(imageSizeX.toString() + "x" + imageSizeY)
                                    showCameraResolution(cropSize.toString() + "x" + cropSize)
                                    showRotationInfo(sensorOrientation.toString())
                                    showInference(lastProcessingTimeMs.toString() + "ms")
                                })
                    }
                    readyForNextImage()
                })
    }

    protected fun onInferenceConfigurationChanged() {
        if (rgbFrameBitmap == null) {
            // Defer creation until we're getting camera frames.
            return
        }
        val device: InputDevice? = getDevice()
        val numThreads: Int = getNumThreads()
        runInBackground { recreateClassifier(device, numThreads) }
    }

    private fun recreateClassifier(device: InputDevice?, numThreads: Int) {
        if (classifier != null) {
            LOGGER.d("Closing classifier.")
            classifier!!.close()
            classifier = null
        }
        try {
            LOGGER.d(
                    "Creating classifier (device=%s, numThreads=%d)", device, numThreads)
            classifier = Classifier.create(this, device, numThreads)
        } catch (e: IOException) {
            LOGGER.e(e, "Failed to create classifier.")
        }

        // Updates the input image size.
        imageSizeX = classifier.getImageSizeX()
        imageSizeY = classifier.getImageSizeY()
    }

    companion object {
        private val LOGGER: Logger = Logger()
        protected val desiredPreviewFrameSize = Size(640, 480)
        private const val TEXT_SIZE_DIP = 10f
    }
}