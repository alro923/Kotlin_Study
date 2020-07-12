package com.google.firebase.example.mlkit.kotlin


import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Camera
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image.Plane
import android.media.ImageReader
import android.os.*
import android.util.Size
import android.view.Surface
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.*
import androidx.annotation.UiThread
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.firebase.example.mlkit.kotlin.env.ImageUtils.convertYUV420SPToARGB8888
import com.google.firebase.example.mlkit.kotlin.env.ImageUtils.convertYUV420ToARGB8888
import com.google.firebase.example.mlkit.kotlin.tflite.Classifier
import devrel.firebase.google.com.firebaseoptions.R
import java.lang.String
import java.nio.ByteBuffer


abstract class CameraActivity : AppCompatActivity(), ImageReader.OnImageAvailableListener,
        Camera.PreviewCallback, View.OnClickListener, AdapterView.OnItemSelectedListener {
    val PERMISSIONS_REQUEST = 1

    val PERMISSION_CAMERA = Manifest.permission.CAMERA
    var previewWidth = 0
    var previewHeight = 0
    lateinit var handler: Handler
    lateinit var handlerThread: HandlerThread
    var useCamera2API = false
    var isProcessingFrame = false
    private val yuvBytes: Array<ByteArray?> = arrayOfNulls<ByteArray>(3)
    var rgbBytes: IntArray? = null
    private var yRowStride = 0
    lateinit var postInferenceCallback: Runnable
    lateinit var imageConverter: Runnable
    lateinit var bottomSheetLayout : LinearLayout
    lateinit var gestureLayout : LinearLayout
    lateinit var sheetBehavior: BottomSheetBehavior<LinearLayout>
    lateinit var recognitionTextView: TextView
    lateinit var recognition1TextView: TextView
    lateinit var recognition2TextView: TextView
    lateinit var recognitionValueTextView: TextView
    lateinit var recognition1ValueTextView: TextView
    lateinit var recognition2ValueTextView: TextView
    lateinit var frameValueTextView: TextView
    lateinit var cropValueTextView: TextView
    lateinit var cameraResolutionTextView: TextView
    lateinit var rotationTextView: TextView
    lateinit var inferenceTimeTextView: TextView
    lateinit var bottomSheetArrowImageView : ImageView
    lateinit var plusImageView: ImageView
    lateinit var minusImageView: ImageView
    lateinit var deviceSpinner: Spinner
    lateinit var threadsTextView: TextView

    private var device = Classifier.Device.CPU
    var numThreads = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.tfe_ic_activity_camera)

        if(hasPermission()){
            setFragment()
        } else{
            requestPremission()
        }
        threadsTextView = findViewById(R.id.threads)
        plusImageView = findViewById(R.id.plus)
        minusImageView = findViewById(R.id.minus)
        deviceSpinner = findViewById(R.id.device_spinner)
        bottomSheetLayout = findViewById(R.id.bottom_sheet_layout)
        gestureLayout = findViewById(R.id.gesture_layout)
        sheetBehavior = BottomSheetBehavior.from(bottomSheetLayout)
        bottomSheetArrowImageView = findViewById(R.id.bottom_sheet_arrow)

        val vto = gestureLayout.viewTreeObserver
        vto.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener{
            override fun onGlobalLayout() {
                if(Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN){
                    gestureLayout.viewTreeObserver.removeGlobalOnLayoutListener(this)
                }else{
                    gestureLayout.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }

                val height = gestureLayout.measuredHeight

                sheetBehavior.peekHeight = height
            }

        })
        sheetBehavior.isHideable = false

        sheetBehavior.setBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onSlide(p0: View, p1: Float) {
            }

            override fun onStateChanged(p0: View, p1: Int) {
                when (p1) {
                    BottomSheetBehavior.STATE_HIDDEN -> {
                    }
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        bottomSheetArrowImageView.setImageResource(R.drawable.icn_chevron_down)
                    }
                    BottomSheetBehavior.STATE_COLLAPSED -> {
                        bottomSheetArrowImageView.setImageResource(R.drawable.icn_chevron_up)
                    }
                    BottomSheetBehavior.STATE_DRAGGING -> {
                    }
                    BottomSheetBehavior.STATE_SETTLING -> bottomSheetArrowImageView.setImageResource(R.drawable.icn_chevron_up)
                }
            }

        })

        recognitionTextView = findViewById(R.id.detected_item)
        recognitionValueTextView = findViewById(R.id.detected_item_value)
        recognition1TextView = findViewById(R.id.detected_item1)
        recognition1ValueTextView = findViewById(R.id.detected_item1_value)
        recognition2TextView = findViewById(R.id.detected_item2)
        recognition2ValueTextView = findViewById(R.id.detected_item2_value)

        frameValueTextView = findViewById(R.id.frame_info)
        cropValueTextView = findViewById(R.id.crop_info)
        cameraResolutionTextView = findViewById(R.id.view_info)
        rotationTextView = findViewById(R.id.rotation_info)
        inferenceTimeTextView = findViewById(R.id.inference_info)

        deviceSpinner.setOnItemSelectedListener(this)
        plusImageView.setOnClickListener(this);
        minusImageView.setOnClickListener(this);

        device = Classifier.Device.valueOf(deviceSpinner.getSelectedItem().toString());
        numThreads = Integer.parseInt(threadsTextView.getText().toString().trim());
    }

    protected fun getRgbByte(): IntArray? {
        // 메소드명 겹쳐서 바꿈
        imageConverter.run()
        return rgbBytes
    }

    protected open fun getLuminanceStride(): Int {
        return yRowStride
    }

    protected fun getLuminance(): ByteArray? {
        return yuvBytes[0]
    }

    override fun onPreviewFrame(data: ByteArray?, camera: Camera?) {
        if (isProcessingFrame) {
            return
        }
        try {
            if (rgbBytes == null) {
                var previewSize: Camera.Size = camera!!.parameters.previewSize
                previewHeight = previewSize.height
                previewWidth = previewSize.width
                rgbBytes = IntArray(previewWidth * previewHeight)
                onPreviewSizeChosen(Size(previewSize.width, previewSize.height), 90)
            }
        } catch (e: Exception) {
            return
        }
        isProcessingFrame = true
        yuvBytes[0] = data
        yRowStride = previewWidth

        imageConverter =
                Runnable { convertYUV420SPToARGB8888(data!!, previewWidth, previewHeight, rgbBytes!!) }
        postInferenceCallback = Runnable {
            camera!!.addCallbackBuffer(data)
            isProcessingFrame = false
        }
        processImage()
    }

    override fun onImageAvailable(reader: ImageReader?) {
        if (previewWidth == 0 || previewHeight == 0) {
            return
        }
        if (rgbBytes == null) {
            rgbBytes = IntArray(previewWidth * previewHeight)
        }
        try {
            val image = reader?.acquireLatestImage()
            if (image == null) {
                return
            }
            if (isProcessingFrame) {
                image.close()
                return
            }
            isProcessingFrame = true
            Trace.beginSection("imageAvailable")
            var planes = image.planes
            fillBytes(planes, yuvBytes)
            yRowStride = planes[0].rowStride
            val uvRowStride = planes[1].rowStride
            val uvPixelStride = planes[1].pixelStride

            imageConverter = Runnable {
                convertYUV420ToARGB8888(
                        yuvBytes.get(0)!!,
                        yuvBytes.get(1)!!,
                        yuvBytes.get(2)!!,
                        previewWidth,
                        previewHeight,
                        yRowStride,
                        uvRowStride,
                        uvPixelStride,
                        rgbBytes!!)
            }

            postInferenceCallback = Runnable {
                image.close()
                isProcessingFrame = false
            }

            processImage()
        } catch (e: Exception) {
            Trace.endSection()
            return
        }
        Trace.endSection()
    }


    @Synchronized
    override fun onStart() {
        super.onStart()
    }

    @Synchronized
    override fun onResume() {
        super.onResume()
        handlerThread = HandlerThread("inference")
        handlerThread.start()
        handler = Handler(handlerThread.looper)
    }

    @Synchronized
    override fun onPause() {

        handlerThread.quitSafely()
        try {
            handlerThread.join()
            handlerThread.quit()
            handler.removeCallbacksAndMessages(null)
            // 여기부분 처리를 잘 몰라서 이렇게 함
        } catch (e: InterruptedException) {

        }
        super.onPause()
    }

    @Synchronized
    override fun onStop() {
        super.onStop()
    }

    @Synchronized
    override fun onDestroy() {
        super.onDestroy()
    }

    @Synchronized
    protected open fun runInBackground(r: Runnable?) {
        if (handler != null) {
            handler.post(r!!)
        }
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out kotlin.String>, grantResults: IntArray) {
        if (requestCode == PERMISSIONS_REQUEST) {
            if (allPermissionsGranted(grantResults)) {
                setFragment()
            } else {
                requestPremission()
            }
        }
    }

    private fun allPermissionsGranted(grantResults: IntArray): Boolean {
        for (result in grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    private fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED
        } else {
            return true
        }
    }

    private fun requestPremission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA)) {
                Toast.makeText(
                        this,
                        "카메라 권한이 필요합니다",
                        Toast.LENGTH_LONG)
                        .show()
            }
            requestPermissions(arrayOf(PERMISSION_CAMERA), PERMISSIONS_REQUEST)
        }
    }

    private fun isHardwareLevelSupported(
            characteristics: CameraCharacteristics, requiredLevel: Int): Boolean {
        val deviceLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)!!!!
        return if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
            requiredLevel == deviceLevel
        } else requiredLevel <= deviceLevel
        // deviceLevel is not LEGACY, can use numerical sort
    }

    private fun chooseCamera(): kotlin.String? {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)

                // We don't use a front facing camera in this sample.
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue
                }
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?: continue

                // Fallback to camera1 API for internal cameras that don't have full support.
                // This should help with legacy situations where using the camera2 API causes
                // distorted or otherwise broken previews.
                useCamera2API = (facing == CameraCharacteristics.LENS_FACING_EXTERNAL
                        || isHardwareLevelSupported(
                        characteristics, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL))

                return cameraId
            }
        } catch (e: CameraAccessException) {

        }
        return null
    }

    protected fun setFragment() {
        var cameraId = chooseCamera()
    }

    protected fun fillBytes(planes: Array<Plane>, yuvBytes: Array<ByteArray?>) {
        for (i in 0 until planes.size) {
            val buffer: ByteBuffer = planes[i].buffer
            if (yuvBytes[i] == null) {
                yuvBytes[i] = ByteArray(buffer.capacity())
            }
            buffer.get(yuvBytes[i])
        }
    }

    protected fun readyForNextImage() {
        if (postInferenceCallback != null) {
            postInferenceCallback.run()
        }
    }

    protected fun getScreenOrientation(): Int {

        return when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_270 -> 270
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_90 -> 90
            else -> 0
        }

    }

    @UiThread
    protected fun showResultsInBottomSheet(results: List<Classifier.Recognition?>?) {
        if (results != null && results.size >= 3) {
            var recognition = results.get(0)
            if (recognition != null) {
                if (recognition.title != null) recognitionTextView.text = recognition.title
                if (recognition.confidence != null) {
                    recognitionValueTextView.text = String.format("%.2f", (100 * recognition.confidence!!)) + "%"
                }
            }

            var recognition1 = results.get(1)
            if (recognition1 != null) {
                if (recognition1.title != null) recognition1TextView.text = recognition1.title
                if (recognition1.confidence != null) {
                    recognition1ValueTextView.text = String.format("%.2f", (100 * recognition1.confidence!!)) + "%"
                }
            }
            var recognition2 = results.get(2)
            if (recognition2 != null) {
                if (recognition2.title != null) recognition2TextView.text = recognition2.title
                if (recognition2.confidence != null) {
                    recognition2ValueTextView.text = String.format("%.2f", (100 * recognition2.confidence!!)) + "%"
                }
            }
        }
    }

    protected fun showFrameInfo(frameInfo: kotlin.String) {
        frameValueTextView.text = frameInfo
    }

    protected fun showCropInfo(cropInfo: kotlin.String) {
        cropValueTextView.text = cropInfo
    }

    protected fun showCameraResolution(cameraInfo: kotlin.String) {
        cameraResolutionTextView.text = cameraInfo
    }

    protected fun showRotationInfo(rotation: kotlin.String) {
        rotationTextView.text = rotation
    }

    protected fun showInference(inferenceTime: kotlin.String) {
        inferenceTimeTextView.text = inferenceTime
    }

    protected fun getDevice(): Classifier.Device {
        return device
    }

    private fun setDevice(device: Classifier.Device) {
        if (this.device != device) {

            this.device = device
            var threadsEnabled = device == Classifier.Device.CPU
            plusImageView.isEnabled = threadsEnabled
            minusImageView.isEnabled = threadsEnabled
            threadsTextView.text = if (threadsEnabled) String.valueOf(numThreads) else "N/A"
            onInferenceConfigurationChanged();
        }

    }

    protected fun getNumberThreads(): Int {
        // 메소드명 겹쳐서 바꿈
        return numThreads
    }

    private fun setNumberThreads(numThreads: Int) {
        // 메소드명 겹쳐서 바꿈
        if (this.numThreads != numThreads) {
            this.numThreads = numThreads
            onInferenceConfigurationChanged()
        }
    }

    protected abstract fun processImage()
    protected abstract fun onPreviewSizeChosen(size: Size, rotation: Int)
    protected abstract fun getLayoutId(): Int
    protected abstract fun getDesiredPreviewFrameSize(): Size
    protected abstract fun onInferenceConfigurationChanged()

    override fun onClick(v: View?) {
        if (v?.id == R.id.plus) {
            var threads = threadsTextView.text.toString().trim()
            var numThreads = Integer.parseInt(threads)
            if (numThreads >= 9) return
            setNumberThreads(++numThreads)
            threadsTextView.text = String.valueOf(numThreads)
        } else if (v?.id == R.id.minus) {
            var threads = threadsTextView.text.toString().trim()
            var numThreads = Integer.parseInt(threads)
            if (numThreads == 1) {
                return
            }
            setNumberThreads(--numThreads)
            threadsTextView.text = String.valueOf(numThreads)
        }
    }

    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        if (parent == deviceSpinner) {
            setDevice(Classifier.Device.valueOf(parent.getItemAtPosition(position).toString()))
        }
    }

    override fun onNothingSelected(parent: AdapterView<*>?) {
        // Do nothing.
    }
}