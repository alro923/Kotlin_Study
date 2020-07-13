package com.google.firebase.example.mlkit.kotlin

import android.R
import android.annotation.SuppressLint
import android.app.Fragment
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.Camera.CameraInfo
import android.hardware.Camera.PreviewCallback
import android.os.Bundle
import android.os.HandlerThread
import android.util.Size
import android.util.SparseIntArray
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView.SurfaceTextureListener
import android.view.View
import android.view.ViewGroup
import com.google.firebase.example.mlkit.kotlin.customview.AutoFitTextureView
import com.google.firebase.example.mlkit.kotlin.env.ImageUtils.getYUVByteSize
import kotlinx.android.synthetic.main.tfe_ic_camera_connection_fragment.*
import java.io.IOException


class LegacyCameraConnectionFragment : Fragment {
    companion object {
        /** Conversion from screen rotation to JPEG orientation.  */
        private val ORIENTATIONS = SparseIntArray()

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }
    }

    private var camera: Camera? = null
    private var imageListener: PreviewCallback? = null
    private var desiredSize: Size? = null

    /** The layout identifier to inflate for this Fragment.  */
    private var layout = 0

    /** An [AutoFitTextureView] for camera preview.  */
    private var textureView: AutoFitTextureView? = null

    /**
     * [TextureView.SurfaceTextureListener] handles several lifecycle events on a [ ].
     */
    private val surfaceTextureListener: SurfaceTextureListener = object : SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(
                texture: SurfaceTexture, width: Int, height: Int) {
            val index: Int = getCameraId()
            camera = Camera.open(index)
            try {
                val parameters = camera?.getParameters()
                val focusModes = parameters?.supportedFocusModes
                if (focusModes != null
                        && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    parameters?.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                }
                val cameraSizes = parameters?.supportedPreviewSizes
                val sizes = arrayOfNulls<Size>(cameraSizes!!.size)
                var i = 0
                for (size in cameraSizes) {
                    sizes[i++] = Size(size.width, size.height)
                }
                val previewSize: Size? =  CameraConnectionFragment.chooseOptimalSize(
                        sizes, desiredSize!!.width, desiredSize!!.height)
                if (previewSize != null) {
                    parameters?.setPreviewSize(previewSize.width, previewSize.height)
                }
                camera?.setDisplayOrientation(90)
                camera?.setParameters(parameters)
                camera?.setPreviewTexture(texture)
            } catch (exception: IOException) {
                camera?.release()
            }
            camera?.setPreviewCallbackWithBuffer(imageListener)
            val s = camera?.getParameters()?.previewSize
            camera?.addCallbackBuffer(ByteArray(getYUVByteSize( /* width= */s!!.height,  /* height= */s.width)))
            textureView!!.setAspectRatio( /* width= */s!!.height,  /* height= */s.width)
            camera?.startPreview()
        }

        override fun onSurfaceTextureSizeChanged(
                texture: SurfaceTexture, width: Int, height: Int) {
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    }


    /** An additional thread for running tasks that shouldn't block the UI.  */
    private var backgroundThread: HandlerThread? = null

    @SuppressLint("ValidFragment")
    constructor(
            imageListener: PreviewCallback?, layout: Int, desiredSize: Size?) {
        this.imageListener = imageListener
        this.layout = layout
        this.desiredSize = desiredSize
    }

    constructor() {}

    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle): View? {
        return inflater.inflate(layout, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        textureView = texture as AutoFitTextureView
        // textureView = view.findViewById<View>(R.id.texture) as AutoFitTextureView
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (textureView!!.isAvailable) {
            if (camera != null) {
                camera!!.startPreview()
            }
        } else {
            textureView!!.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        stopCamera()
        stopBackgroundThread()
        super.onPause()
    }

    /** Starts a background thread and its [Handler].  */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread!!.start()
    }

    /** Stops the background thread and its [Handler].  */
    private fun stopBackgroundThread() {
        backgroundThread!!.quitSafely()
        try {
            backgroundThread!!.join()
            backgroundThread = null
        } catch (e: InterruptedException) {
        }
    }

    protected fun stopCamera() {
        if (camera != null) {
            camera!!.stopPreview()
            camera!!.setPreviewCallback(null)
            camera!!.release()
            camera = null
        }
    }

    private fun getCameraId(): Int {
        val ci = CameraInfo()
        for (i in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(i, ci)
            if (ci.facing == CameraInfo.CAMERA_FACING_BACK) return i
        }
        return -1 // No camera found
    }
}