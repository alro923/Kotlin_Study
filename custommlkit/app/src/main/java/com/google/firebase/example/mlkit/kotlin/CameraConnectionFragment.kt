package com.google.firebase.example.mlkit.kotlin


import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.util.SparseIntArray
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView.SurfaceTextureListener
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.google.firebase.example.mlkit.kotlin.customview.AutoFitTextureView
import org.tensorflow.lite.examples.classification.R
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList


/**
 * Camera Connection Fragment that captures images from camera.
 *
 *
 * Instantiated by newInstance.
 */
@SuppressWarnings("FragmentNotInstantiable")
open class CameraConnectionFragment : androidx.fragment.app.Fragment() {

    private val FRAGMENT_DIALOG = "dialog"

    companion object {
        init {
            val ORIENTATIONS = SparseIntArray()
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }
        fun chooseOptimalSize(choices: Array<Size?>, width: Int, height: Int): Size? {
            val MINIMUM_PREVIEW_SIZE = 320
            val minSize = Math.max(Math.min(width, height), MINIMUM_PREVIEW_SIZE)
            val desiredSize = Size(width, height)

            // Collect the supported resolutions that are at least as big as the preview Surface
            var exactSizeFound = false
            val bigEnough: MutableList<Size?> = ArrayList()
            val tooSmall: MutableList<Size?> = ArrayList()
            for (option in choices) {
                if (option == desiredSize) {
                    // Set the size but don't return yet so that remaining sizes will still be logged.
                    exactSizeFound = true
                }
                if (option != null) {
                    if (option.height >= minSize && option.width >= minSize) {
                        bigEnough.add(option)
                    } else {
                        tooSmall.add(option)
                    }
                }
            }
            if (exactSizeFound) {
                return desiredSize
            }

            // Pick the smallest of those, assuming we found any
            return if (bigEnough.size > 0) {
                val chosenSize: Size? = Collections.min(bigEnough, CompareSizesByArea())
                chosenSize
            } else {
                choices[0]
            }
        }
    }

    private val cameraOpenCloseLock: Semaphore = Semaphore(1)
    lateinit var imageListener : OnImageAvailableListener
    lateinit var inputSize: Size
    private var layout = 0


    private var cameraConnectionCallback: ConnectionCallback? = null
    private val captureCallback: CaptureCallback = object : CaptureCallback() {
        override fun onCaptureProgressed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                partialResult: CaptureResult) {
        }

        override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult) {
        }
    }

    private var cameraId: String? = null

    private var textureView: AutoFitTextureView? = null

    private var captureSession: CameraCaptureSession? = null

    private var cameraDevice: CameraDevice? = null

    private var sensorOrientation: Int? = null

    private var previewSize: Size? = null

    private var backgroundThread: HandlerThread? = null

    private var backgroundHandler: Handler? = null

    private val surfaceTextureListener: SurfaceTextureListener = object : SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(
                texture: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(
                texture: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    }

    private var previewReader: ImageReader? = null

    /** [CaptureRequest.Builder] for the camera preview  */
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    /** [CaptureRequest] generated by [.previewRequestBuilder]  */
    private var previewRequest: CaptureRequest? = null

    /** [CameraDevice.StateCallback] is called when [CameraDevice] changes its state.  */
    private val stateCallback: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cd: CameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here.
            cameraOpenCloseLock.release()
            cameraDevice = cd
            createCameraPreviewSession()
        }
        private fun createCameraPreviewSession() {
            try {
                val texture = textureView!!.surfaceTexture!!

                // We configure the size of default buffer to be the size of camera preview we want.
                texture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)

                // This is the output Surface we need to start preview.
                val surface = Surface(texture)

                // We set up a CaptureRequest.Builder with the output Surface.
                previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                previewRequestBuilder!!.addTarget(surface)

                // Create the reader for the preview frames.
                previewReader = ImageReader.newInstance(
                        previewSize!!.width, previewSize!!.height, ImageFormat.YUV_420_888, 2)
                previewReader!!.setOnImageAvailableListener(imageListener, backgroundHandler)
                previewRequestBuilder!!.addTarget(previewReader!!.getSurface())

                // Here, we create a CameraCaptureSession for camera preview.
                cameraDevice!!.createCaptureSession(
                        Arrays.asList(surface, previewReader!!.getSurface()),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                                // The camera is already closed
                                if (null == cameraDevice) {
                                    return
                                }

                                // When the session is ready, we start displaying the preview.
                                captureSession = cameraCaptureSession
                                try {
                                    // Auto focus should be continuous for camera preview.
                                    previewRequestBuilder!!.set(
                                            CaptureRequest.CONTROL_AF_MODE,
                                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    // Flash is automatically enabled when necessary.
                                    previewRequestBuilder!!.set(
                                            CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)

                                    // Finally, we start displaying the camera preview.
                                    previewRequest = previewRequestBuilder!!.build()
                                    captureSession!!.setRepeatingRequest(
                                            previewRequest!!, captureCallback, backgroundHandler)
                                } catch (e: CameraAccessException) {
                                }
                            }

                            override fun onConfigureFailed(p0: CameraCaptureSession) {
                            }
                        },
                        null)
            } catch (e: CameraAccessException) {
            }
        }
        override fun onDisconnected(cd: CameraDevice) {
            cameraOpenCloseLock.release()
            cd.close()
            cameraDevice = null
        }

        override fun onError(cd: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            cd.close()
            cameraDevice = null
            val activity: Activity? = activity
            activity?.finish()
        }
    }

    @SuppressLint("ValidFragment")
    private fun CameraConnectionFragment(
            connectionCallback: ConnectionCallback,
            imageListener: OnImageAvailableListener,
            layout: Int,
            inputSize: Size) {
        cameraConnectionCallback = connectionCallback
        this.imageListener = imageListener
        this.layout = layout
        this.inputSize = inputSize
    }


    fun newInstance(
            callback: ConnectionCallback?,
            imageListener: OnImageAvailableListener?,
            layout: Int,
            inputSize: Size?) {
        return CameraConnectionFragment(callback!!, imageListener!!, layout, inputSize!!)
    }
    private fun showToast(text: String) {
        val activity: Activity? = activity
        activity?.runOnUiThread { Toast.makeText(activity, text, Toast.LENGTH_SHORT).show() }
    }

    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(layout, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        textureView = view.findViewById(R.id.texture)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()

        if (textureView!!.isAvailable) {
            openCamera(textureView!!.width, textureView!!.height)
        } else {
            textureView!!.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }
    fun setCamera(cameraId: String?) {
        this.cameraId = cameraId
    }
    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            if (null != captureSession) {
                captureSession!!.close()
                captureSession = null
            }
            if (null != cameraDevice) {
                cameraDevice!!.close()
                cameraDevice = null
            }
            if (null != previewReader) {
                previewReader!!.close()
                previewReader = null
            }
        } catch (e: InterruptedException) {
            throw java.lang.RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("ImageListener")
        backgroundThread!!.start()
        backgroundHandler = Handler(backgroundThread!!.getLooper())
    }
    private fun stopBackgroundThread() {
        backgroundThread!!.quitSafely()
        try {
            backgroundThread!!.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val activity: Activity? = activity
        if (null == textureView || null == previewSize || null == activity) {
            return
        }
        val rotation = activity.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0F, 0F, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0F, 0F, previewSize!!.height.toFloat(), previewSize!!.width.toFloat())
        val centerX: Float = viewRect.centerX()
        val centerY: Float = viewRect.centerY()
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = Math.max(
                    viewHeight.toFloat() / previewSize!!.height,
                    viewWidth.toFloat() / previewSize!!.width)
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180F, centerX, centerY)
        }
        textureView!!.setTransform(matrix)
    }

    interface ConnectionCallback {
        fun onPreviewSizeChosen(size: Size?, cameraRotation: Int)
    }
    internal class CompareSizesByArea : Comparator<Size?> {

        override fun compare(lhs: Size?, rhs: Size?): Int {
            return java.lang.Long.signum(
                    lhs!!.width.toLong() * lhs.height - rhs!!.width.toLong() * rhs.height)
        }
    }
    private fun setUpCameraOutputs() {
        val activity: Activity? = activity
        val manager = activity!!.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val characteristics = manager.getCameraCharacteristics(cameraId!!)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)

            // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
            // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
            // garbage capture data.
            previewSize = chooseOptimalSize(
                    map!!.getOutputSizes(SurfaceTexture::class.java),
                    inputSize.width,
                    inputSize.height)

            // We fit the aspect ratio of TextureView to the size of preview we picked.
            val orientation = resources.configuration.orientation
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                textureView!!.setAspectRatio(previewSize!!.width, previewSize!!.height)
            } else {
                textureView!!.setAspectRatio(previewSize!!.height, previewSize!!.width)
            }
        } catch (e: CameraAccessException) {
        } catch (e: NullPointerException) {

        }
        cameraConnectionCallback?.onPreviewSizeChosen(previewSize, sensorOrientation!!)
    }



    private fun openCamera(width: Int, height: Int) {
        setUpCameraOutputs()
        configureTransform(width, height)
        val activity: Activity? = activity
        val manager = activity!!.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }

            if (ActivityCompat.checkSelfPermission(context!!, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            manager.openCamera(cameraId!!, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = textureView!!.surfaceTexture!!

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)

            // This is the output Surface we need to start preview.
            val surface = Surface(texture)

            // We set up a CaptureRequest.Builder with the output Surface.
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder!!.addTarget(surface)

            // Create the reader for the preview frames.
            previewReader = ImageReader.newInstance(
                    previewSize!!.width, previewSize!!.height, ImageFormat.YUV_420_888, 2)
            previewReader!!.setOnImageAvailableListener(imageListener, backgroundHandler)
            previewRequestBuilder!!.addTarget(previewReader!!.surface)


            cameraDevice!!.createCaptureSession(
                    listOf(surface, previewReader!!.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                            // The camera is already closed
                            if (null == cameraDevice) {
                                return
                            }

                            // When the session is ready, we start displaying the preview.
                            captureSession = cameraCaptureSession
                            try {
                                // Auto focus should be continuous for camera preview.
                                previewRequestBuilder!!.set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                // Flash is automatically enabled when necessary.
                                previewRequestBuilder!!.set(
                                        CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)

                                // Finally, we start displaying the camera preview.
                                previewRequest = previewRequestBuilder!!.build()
                                captureSession!!.setRepeatingRequest(
                                        previewRequest!!, captureCallback, backgroundHandler)
                            } catch (e: CameraAccessException) {
                            }
                        }

                        override fun onConfigureFailed(p0: CameraCaptureSession) {
                            showToast("Failed")
                        }
                    },
                    null)
        } catch (e: CameraAccessException) {
        }
    }


}