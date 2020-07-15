package com.google.firebase.example.mlkit.kotlin


import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.Camera
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image.Plane
import android.media.ImageReader
import android.net.Uri
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
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.firebase.example.mlkit.kotlin.env.ImageUtils.convertYUV420SPToARGB8888
import com.google.firebase.example.mlkit.kotlin.env.ImageUtils.convertYUV420ToARGB8888
import com.google.firebase.example.mlkit.kotlin.tflite.Classifier.*
import com.google.firebase.example.mlkit.kotlin.tflite.Classifier.Device.*
import kotlinx.android.synthetic.main.tfe_ic_layout_bottom_sheet.*
import org.tensorflow.lite.examples.classification.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.*


abstract class AlbumActivity : AppCompatActivity(), ImageReader.OnImageAvailableListener,
        View.OnClickListener, AdapterView.OnItemSelectedListener {
    var FROM_ALBUM = 1    // onActivityResult 식별자
    var FROM_CAMERA = 2   // 카메라는 사용 안함

    private val PERMISSIONS_REQUEST = 1
    // private val PERMISSION_CAMERA = Manifest.permission.CAMERA
    var previewWidth = 0
    var previewHeight = 0
    lateinit var handler: Handler
    lateinit var handlerThread: HandlerThread
    // var useCamera2API = false
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

    private var device = CPU
    var numThreads = -1

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.tfe_ic_activity_camera)

        Log.e("로그", "onCreate")
//        if(hasPermission()){
//            setFragment()
//        } else{
//            requestPremission()
//        }

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
                    BottomSheetBehavior.STATE_HALF_EXPANDED -> {
                        // bottomSheetArrowImageView.setImageResource(R.drawable.icn_chevron_up)
                    }
                }
            }

        })


        recognitionTextView = detected_item
        recognitionValueTextView = detected_item_value
        recognition1TextView = detected_item1
        recognition1ValueTextView = detected_item1_value
        recognition2TextView = detected_item2
        recognition2ValueTextView = detected_item2_value

        frameValueTextView = frame_info
        cropValueTextView = crop_info
        cameraResolutionTextView = view_info
        rotationTextView = rotation_info
        inferenceTimeTextView = inference_info

        deviceSpinner.onItemSelectedListener = this
        plusImageView.setOnClickListener(this);
        minusImageView.setOnClickListener(this);

        device = valueOf(deviceSpinner.selectedItem.toString());
        numThreads = Integer.parseInt(threadsTextView.text.toString().trim());
    }
    override fun onActivityResult(requestCode : Int, resultCode : Int, data : Intent?) {

        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != FROM_ALBUM || resultCode != RESULT_OK)
            return
        // 일단 앨범인 경우만 처리
        try {
            val stream = data?.data?.let { contentResolver.openInputStream(it) }
            val bitmapInput = BitmapFactory.decodeStream(stream)
            if (stream != null) {
                stream.close()
            }

            val inputbitmap = assetsToBitmap("sample.jpeg")

            val bitmap = Bitmap.createScaledBitmap(inputbitmap!!, 224, 224, true)
            val batchNum = 0
            val input = Array(1) { Array(224) { Array(224) { FloatArray(3) } } }
            val output = Array(1){FloatArray(5)}
            // val output = floatArrayOf(0F)

            //model.tflite
            // [  1 224 224   3]
            // <class 'numpy.float32'>
            // [1 5]
            // <class 'numpy.float32'>
            for (x in 0..223) {
                for (y in 0..223) {
                    val pixel = bitmap.getPixel(x, y)
                    // Normalize channel values to [-1.0, 1.0]. This requirement varies by
                    // model. For example, some models might require values to be normalized
                    // to the range [0.0, 1.0] instead.
                    input[batchNum][x][y][0] = (Color.red(pixel) - 127) / 255.0f
                    input[batchNum][x][y][1] = (Color.green(pixel) - 127) / 255.0f
                    input[batchNum][x][y][2] = (Color.blue(pixel) - 127) / 255.0f
                }
            }



        }
        catch (e: IOException){
            e.printStackTrace()
        }
    }
    // Method to get a bitmap from assets
    private fun assetsToBitmap(fileName:String):Bitmap?{
        return try{
            val stream = assets.open(fileName)
            BitmapFactory.decodeStream(stream)
        }catch (e:IOException){
            e.printStackTrace()
            null
        }
    }
    // Method to save an bitmap to a file
    private fun bitmapToFile(bitmap:Bitmap): Uri {
        // Get the context wrapper
        val wrapper = ContextWrapper(applicationContext)

        // Initialize a new file instance to save bitmap object
        var file = wrapper.getDir("Images", Context.MODE_PRIVATE)
        file = File(file,"${UUID.randomUUID()}.jpg")

        try{
            // Compress the bitmap and save in jpg format
            val stream: OutputStream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG,100,stream)
            stream.flush()
            stream.close()
        }catch (e:IOException){
            e.printStackTrace()
        }

        // Return the saved bitmap uri
        return Uri.parse(file.absolutePath)
    }
    // Extension function to show toast message
    fun Context.toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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

    fun onPreviewFrame(data: ByteArray?, camera: Camera?) {
        if (isProcessingFrame) {
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
            Log.e("Camera", "onPreviewFrame catch")
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
        Log.e("Camera", "onPreviewFrame process image")
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
            val image = reader?.acquireLatestImage() ?: return
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


//    @RequiresApi(Build.VERSION_CODES.M)
//    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out kotlin.String>, grantResults: IntArray) {
//        if (requestCode == PERMISSIONS_REQUEST) {
//            if (allPermissionsGranted(grantResults)) {
//                setFragment()
//            } else {
//                requestPremission()
//            }
//        }
//    }

//    private fun allPermissionsGranted(grantResults: IntArray): Boolean {
//        for (result in grantResults) {
//            if (result != PackageManager.PERMISSION_GRANTED) {
//                return false
//            }
//        }
//        return true
//    }
//
//    private fun hasPermission(): Boolean {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//            return checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED
//        } else {
//            return true
//        }
//    }
//
//    private fun requestPremission() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//            if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA)) {
//                Toast.makeText(
//                        this,
//                        "카메라 권한이 필요합니다",
//                        Toast.LENGTH_LONG)
//                        .show()
//            }
//            requestPermissions(arrayOf(PERMISSION_CAMERA), PERMISSIONS_REQUEST)
//        }
//    }
//
//    private fun isHardwareLevelSupported(
//            characteristics: CameraCharacteristics, requiredLevel: Int): Boolean {
//        val deviceLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)!!!!
//        return if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
//            requiredLevel == deviceLevel
//        } else requiredLevel <= deviceLevel
//        // deviceLevel is not LEGACY, can use numerical sort
//    }
//
//    @RequiresApi(Build.VERSION_CODES.M)
//    private fun chooseCamera(): kotlin.String? {
//        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
//        try {
//            for (cameraId in manager.cameraIdList) {
//                val characteristics = manager.getCameraCharacteristics(cameraId)
//
//                // We don't use a front facing camera in this sample.
//                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
//                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
//                    continue
//                }
//                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
//                        ?: continue
//
//                // Fallback to camera1 API for internal cameras that don't have full support.
//                // This should help with legacy situations where using the camera2 API causes
//                // distorted or otherwise broken previews.
//                useCamera2API = (facing == CameraCharacteristics.LENS_FACING_EXTERNAL
//                        || isHardwareLevelSupported(
//                        characteristics, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL))
//
//                return cameraId
//            }
//        } catch (e: CameraAccessException) {
//
//        }
//        return null
//    }
//
//    @RequiresApi(Build.VERSION_CODES.M)
//    protected fun setFragment() {
//        var cameraId = chooseCamera()
//    }
//
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

    protected fun getDevice(): Device {
        return device
    }

    private fun setDevice(device: Device) {
        if (this.device != device) {

            this.device = device
            var threadsEnabled = device == CPU
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