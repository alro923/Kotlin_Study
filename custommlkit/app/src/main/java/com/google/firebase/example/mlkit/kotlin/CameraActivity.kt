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
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.annotation.UiThread
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.firebase.example.mlkit.kotlin.env.ImageUtils.convertYUV420SPToARGB8888
import com.google.firebase.example.mlkit.kotlin.env.ImageUtils.convertYUV420ToARGB8888
import com.google.firebase.example.mlkit.kotlin.tflite.Classifier.Device
import com.google.firebase.example.mlkit.kotlin.tflite.Classifier.Device.CPU
import com.google.firebase.example.mlkit.kotlin.tflite.Classifier.Device.valueOf
import com.google.firebase.example.mlkit.kotlin.tflite.Classifier.Recognition
import org.tensorflow.lite.examples.classification.R
import java.nio.ByteBuffer


abstract class CameraActivity : AppCompatActivity(), ImageReader.OnImageAvailableListener,
        Camera.PreviewCallback, View.OnClickListener, AdapterView.OnItemSelectedListener {
    private val PERMISSIONS_REQUEST = 1
    private val PERMISSION_CAMERA = Manifest.permission.CAMERA
    var previewWidth = 0
    var previewHeight = 0
    private var handler: Handler?  = null
    private var handlerThread: HandlerThread? = null
    private var useCamera2API = false
    private var isProcessingFrame = false
    private val yuvBytes: Array<ByteArray?> = arrayOfNulls<ByteArray>(3)
    private var rgbBytes: IntArray? = null
    private var yRowStride = 0
    private lateinit var postInferenceCallback: Runnable
    private lateinit var imageConverter: Runnable
    private lateinit var bottomSheetLayout : LinearLayout
    private lateinit var gestureLayout : LinearLayout
    private lateinit var sheetBehavior: BottomSheetBehavior<LinearLayout>

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

    private var device = CPU
    var numThreads = -1

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.e("로그", "onCreate $this")

        super.onCreate(null)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.tfe_ic_activity_camera)


        if(hasPermission()){
            setFragment()
        } else{
            requestPermission()
        }

        Log.e("로그", "got permission")
        threadsTextView = findViewById(R.id.threads)
        plusImageView = findViewById(R.id.plus)
        minusImageView = findViewById(R.id.minus)
        deviceSpinner = findViewById(R.id.device_spinner)
        bottomSheetLayout = findViewById(R.id.bottom_sheet_layout)
        gestureLayout = findViewById(R.id.gesture_layout)
        sheetBehavior = BottomSheetBehavior.from(this.bottomSheetLayout)
        bottomSheetArrowImageView = findViewById(R.id.bottom_sheet_arrow)

        val vto = gestureLayout.viewTreeObserver
        vto.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener{
            override fun onGlobalLayout() {
                if(Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN){
                    gestureLayout.viewTreeObserver.removeGlobalOnLayoutListener(this)
                }else{
                    gestureLayout.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
                val width = bottomSheetLayout.measuredWidth
                val height = gestureLayout.measuredHeight

                sheetBehavior.peekHeight = height
            }

        })
        sheetBehavior.isHideable = false

        sheetBehavior.setBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {

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
                    BottomSheetBehavior.STATE_HALF_EXPANDED -> {
                        // bottomSheetArrowImageView.setImageResource(R.drawable.icn_chevron_up)
                    }
                }
            }

            override fun onSlide(p0: View, p1: Float) {
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

        deviceSpinner.onItemSelectedListener = this
        plusImageView.setOnClickListener(this)
        minusImageView.setOnClickListener(this)

        device = valueOf(deviceSpinner.selectedItem.toString());
        numThreads = Integer.parseInt(threadsTextView.text.toString().trim());
    }

    protected fun getRgbBytes(): IntArray? {
        // 메소드명 겹쳐서 바꿈
        // 원래 쓰이는데 안쓰임 확임해봐야함
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
            Log.e("로그", "Dropping frame!")
            return
        }
        try {
            Log.e("Camera", "onPreviewFrame Try")
            if (rgbBytes == null) {
                val previewSize: Camera.Size = camera!!.parameters.previewSize
                previewHeight = previewSize.height
                previewWidth = previewSize.width
                rgbBytes = IntArray(previewWidth * previewHeight)
                onPreviewSizeChosen(Size(previewSize.width, previewSize.height), 90)
                Log.e("Camera", "onPreviewFrame Try if")
            }
        } catch (e: Exception) {
            Log.e("로그", "Exception!")
            return
        }

        isProcessingFrame = true
        yuvBytes[0] = data
        yRowStride = previewWidth

        imageConverter =
                Runnable() {
                    @Override
                    fun run(){
                        convertYUV420SPToARGB8888(data!!, previewWidth, previewHeight, rgbBytes!!)
                    } }
        postInferenceCallback = Runnable() {
            @Override
            fun run(){
                camera!!.addCallbackBuffer(data)
                isProcessingFrame = false
            }
        }
        processImage()
        Log.e("로그", "onPreviewFrame process image")
    }

    override fun onImageAvailable(reader: ImageReader?) {
        if (previewWidth == 0 || previewHeight == 0) {
            return
        }
        if (rgbBytes == null) {
            rgbBytes = IntArray(previewWidth * previewHeight)
        }
        try {
            Log.e("Camera", "onImageAvailable")
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
            val planes = image.planes
            fillBytes(planes, yuvBytes)
            yRowStride = planes[0].rowStride
            val uvRowStride = planes[1].rowStride
            val uvPixelStride = planes[1].pixelStride

            imageConverter = Runnable() {
                @Override
                fun run(){
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

            }

            postInferenceCallback = Runnable() {
                @Override
                fun run(){
                    image.close()
                    isProcessingFrame = false
                }
            }

            processImage()
        } catch (e: Exception) {
            Log.e("로그", "Exception!")
            Trace.endSection()
            return
        }
        Trace.endSection()
    }


    @Synchronized
    override fun onStart() {
        Log.e("로그", "onStart $this")
        super.onStart()
    }

    @Synchronized
    override fun onResume() {
        Log.e("로그", "onResume $this")
        super.onResume()
        handlerThread = HandlerThread("inference")
        handlerThread?.start()
        handler = Handler(handlerThread!!.looper)
    }

    @Synchronized
    override fun onPause() {
        Log.e("로그", "onPause $this")
        handlerThread?.quitSafely()
        try {
            handlerThread?.join()
            handlerThread = null
            handler = null
            // 여기부분 처리를 잘 몰라서 이렇게 함
            // 원래대로 null 로 처리함
        } catch (e: InterruptedException) {
            Log.e("로그", "Exception!")
        }
        super.onPause()
    }

    @Synchronized
    override fun onStop() {
        Log.e("로그", "onStop $this")
        super.onStop()
    }

    @Synchronized
    override fun onDestroy() {
        Log.e("로그", "onDestroy $this")
        super.onDestroy()
    }

    @Synchronized
    protected open fun runInBackground(r: Runnable?) {
        if (handler != null) {
            handler?.post(r!!)
        }
    }


    @RequiresApi(Build.VERSION_CODES.M)
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == PERMISSIONS_REQUEST) {
            if (allPermissionsGranted(grantResults)) {
                setFragment()
            } else {
                requestPermission()
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

    private fun requestPermission() {
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

    @RequiresApi(Build.VERSION_CODES.M)
    private fun chooseCamera(): String? {
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
                Log.e("로그", "Camera API lv2? : $useCamera2API")
                return cameraId
            }
        } catch (e: CameraAccessException) {
            Log.e("로그", "Not allowed to access camera")
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.M)
    protected fun setFragment() {
        // 여기 다 없어졌넹
        var cameraId = chooseCamera()
        var fragment : Fragment? = null

        if (useCamera2API) {
            val camera2Fragment: CameraConnectionFragment = CameraConnectionFragment.newInstance(
                    { size : Size, rotation : Int->
                        previewHeight = size.height
                        previewWidth = size.width
                        onPreviewSizeChosen(size, rotation)
                    },
                    this,
                    getLayoutId(),
                    getDesiredPreviewFrameSize())
            camera2Fragment.setCamera(cameraId)
            Log.e("로그", "Camera Id is $cameraId")
            fragment = camera2Fragment
            // 이 부분이 할당이 안 되는거 같은데 뭐지
        } else {
            // LegacyCameraConnectionFragment 가 Fragment 인데 mismatch 뜸
            fragment = LegacyCameraConnectionFragment(this, getLayoutId(), getDesiredPreviewFrameSize())
        }

    }

    protected fun fillBytes(planes: Array<Plane>, yuvBytes: Array<ByteArray?>) {
        for (i in planes.indices) {
            val buffer: ByteBuffer = planes[i].buffer
            if (yuvBytes[i] == null) {
                Log.e("로그", "Initializing buffer $i at size ${buffer.capacity()}")
                yuvBytes[i] = ByteArray(buffer.capacity())
            }
            buffer.get(yuvBytes[i]!!)
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
    protected fun showResultsInBottomSheet(results: List<Recognition?>?) {
        if (!(results == null || results.size < 3)) {
            val recognition = results.get(0)
            if (recognition != null) {
                if (recognition.getTitle() != null) recognitionTextView.text = recognition.getTitle()
                if (recognition.getConfidence() != null) {
                    recognitionValueTextView.text = String.format("%.2f", (100 * recognition.getConfidence()!!)) + "%"
                }
            }

            val recognition1 = results.get(1)
            if (recognition1 != null) {
                if (recognition1.getTitle() != null) recognition1TextView.text = recognition1.getTitle()
                if (recognition1.getConfidence() != null) {
                    recognition1ValueTextView.text = String.format("%.2f", (100 * recognition1.getConfidence()!!)) + "%"
                }
            }

            val recognition2 = results.get(2)
            if (recognition2 != null) {
                if (recognition2.getTitle() != null) recognition2TextView.text = recognition2.getTitle()
                if (recognition2.getConfidence() != null) {
                    recognition2ValueTextView.text = String.format("%.2f", (100 * recognition2.getConfidence()!!)) + "%"
                }
            }
        }
    }

    protected fun showFrameInfo(frameInfo: String) {
        frameValueTextView.text = frameInfo
    }

    protected fun showCropInfo(cropInfo: String) {
        cropValueTextView.text = cropInfo
    }

    protected fun showCameraResolution(cameraInfo: String) {
        cameraResolutionTextView.text = cameraInfo
    }

    protected fun showRotationInfo(rotation: String) {
        rotationTextView.text = rotation
    }

    protected fun showInference(inferenceTime: String) {
        inferenceTimeTextView.text = inferenceTime
    }

    protected fun getDevice(): Device {
        return device
    }

    private fun setDevice(device: Device) {
        if (this.device != device) {
            Log.e("로그", "Updating device $device")
            this.device = device
            val threadsEnabled = device == CPU
            plusImageView.isEnabled = threadsEnabled
            minusImageView.isEnabled = threadsEnabled
            threadsTextView.text = (if (threadsEnabled) numThreads else "N/A").toString()
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
            Log.e("로그","Updating numThreads: $numThreads")
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
            val threads = threadsTextView.text.toString().trim()
            var numThreads = Integer.parseInt(threads)
            if (numThreads >= 9) return
            setNumberThreads(++numThreads)
            threadsTextView.text = numThreads.toString()
        } else if (v?.id == R.id.minus) {
            val threads = threadsTextView.text.toString().trim()
            var numThreads = Integer.parseInt(threads)
            if (numThreads == 1) {
                return
            }
            setNumberThreads(--numThreads)
            threadsTextView.text = numThreads.toString()
        }
    }

    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        if (parent == deviceSpinner) {
            setDevice(valueOf(parent.getItemAtPosition(position).toString()))
        }
    }

    override fun onNothingSelected(parent: AdapterView<*>?) {
        // Do nothing.
    }
}