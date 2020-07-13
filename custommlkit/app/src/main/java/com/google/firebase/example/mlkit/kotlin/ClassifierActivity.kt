package com.google.firebase.example.mlkit.kotlin

import android.graphics.Bitmap
import android.graphics.Typeface
import android.media.ImageReader.OnImageAvailableListener
import android.os.SystemClock
import android.util.Size
import android.util.TypedValue
import com.google.firebase.example.mlkit.kotlin.env.BorderedText
import com.google.firebase.example.mlkit.kotlin.tflite.Classifier
import org.tensorflow.lite.examples.classification.R
import java.io.IOException
import java.net.URI.create


class ClassifierActivity : CameraActivity(), OnImageAvailableListener {
    val DESIRED_PREVIEW_SIZE = Size(640, 480)
    val TEXT_SIZE_DIP : Float= 10F
    var rgbFrameBitmap : Bitmap? = null
    var lastProcessingTimeMs : Long = 0L
    var sensorOrientation = 0
    lateinit var classifier : Classifier
    lateinit var borderedText: BorderedText
    var imageSizeX = 0
    var imageSizeY= 0

    override fun getLayoutId(): Int {
        return R.layout.tfe_ic_camera_connection_fragment
    }

    override fun getDesiredPreviewFrameSize(): Size {
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
    }
    override fun processImage() {
        rgbFrameBitmap!!.setPixels(rgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight)
        val cropSize = Math.min(previewWidth, previewHeight)

        runInBackground(
                Runnable {
                    if (classifier != null) {
                        val startTime = SystemClock.uptimeMillis()
                        val results = classifier.recognizeImage(rgbFrameBitmap!!, sensorOrientation)
                        lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime
                        runOnUiThread {
                            showResultsInBottomSheet(results)
                            showFrameInfo(previewWidth.toString() + "x" + previewHeight)
                            showCropInfo(imageSizeX.toString() + "x" + imageSizeY)
                            showCameraResolution(cropSize.toString() + "x" + cropSize)
                            showRotationInfo(sensorOrientation.toString())
                            showInference(lastProcessingTimeMs.toString() + "ms")
                        }
                    }
                    readyForNextImage()
                })
    }
    override fun onInferenceConfigurationChanged(){
        if (rgbFrameBitmap == null) {
            // Defer creation until we're getting camera frames.
            return
        }
        val device: Classifier.Device = getDevice()
        val numThreads = numThreads
        runInBackground(Runnable { recreateClassifier(device, numThreads) })

    }

    private fun recreateClassifier(device : Classifier.Device, numThreads : Int){
        if(classifier != null){
            classifier.close()
        }
        try{
            classifier = Classifier.create(this, device, numThreads)!!
        }catch (e : IOException){

        }

        imageSizeX = classifier.getImageSizeX()
        imageSizeY = classifier.getImageSizeY()
    }
}