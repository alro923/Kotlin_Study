package com.google.firebase.example.mlkit.kotlin

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.BuildConfig
import com.google.firebase.ml.common.FirebaseMLException
import com.google.firebase.ml.common.modeldownload.FirebaseModelDownloadConditions
import com.google.firebase.ml.common.modeldownload.FirebaseModelManager
import com.google.firebase.ml.custom.*
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.cloud.FirebaseVisionCloudDetectorOptions
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata
import com.google.firebase.ml.vision.label.FirebaseVisionCloudImageLabelerOptions
import devrel.firebase.google.com.firebaseoptions.R
import kotlinx.android.synthetic.main.activity_main.*
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

// 이게 진짜임
class MainActivity : AppCompatActivity() {


     // private val picPath: String? = "sample.jpeg"
     // private val picBitmap = BitmapFactory.decodeFile("src/main/assets/sample.jpeg")
     // private val yourInputImage = picBitmap
    private val yourInputImage: Bitmap get() = Bitmap.createBitmap(0, 0, Bitmap.Config.ALPHA_8)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)



        // 결과값 출력하는 TextView
        val tv_output = findViewById<TextView>(R.id.tv_output)

        // simple_1.tflite 모델 사용하는 onClick, 잘된다!
        findViewById<View>(R.id.button_1).setOnClickListener {
            val input = intArrayOf(3)
            val output = intArrayOf(0)

            val tflite: Interpreter = getTfliteInterpreter("simple_1.tflite")!!
            tflite.run(input, output)
            tv_output.text = output[0].toString()
        }

        // model.tflite 모델 사용하는 onClick
        findViewById<View>(R.id.button_2).setOnClickListener {
            // Get the bitmap from assets and display into image view
            val inputbitmap = assetsToBitmap("sample.jpeg")

            val bitmap = Bitmap.createScaledBitmap(inputbitmap!!, 224, 224, true)
            val batchNum = 0
            val input = Array(1) { Array(224) { Array(224) { FloatArray(3) } } }
            val output = intArrayOf(0)

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

            val classifiertflite: Interpreter = getTfliteInterpreter("model.tflite")!!
            classifiertflite.run(input, output)

            tv_output.text = output.toString()
            Log.d("EA" , "input is : ${input.toString()}")
            Log.d("EA" , "output is : ${output.toString()}")
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


    // from simple model
    // 모델 파일 인터프리터를 생성하는 공통 함수
    // loadModelFile 함수에 예외가 포함되어 있기 때문에 반드시 try, catch 블록이 필요하다.
    fun getTfliteInterpreter(modelPath: String): Interpreter? {
        try {
            return Interpreter(loadModelFile(this@MainActivity, modelPath)!!)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    // MappedByteBuffer 바이트 버퍼를 Interpreter 객체에 전달하면 모델 해석을 할 수 있다.
    // tensorflow lite 홈페이지 참고
    @Throws(IOException::class)
    private fun loadModelFile(activity: Activity, modelPath: String): MappedByteBuffer? {
        val fileDescriptor = activity.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    // 여기 밑에꺼는 안쓴거임 파이어베이스 연결할때 쓰려고 냅둔거임!
    // initial
    fun buildCloudVisionOptions() {
        // [START ml_build_cloud_vision_options]
        val options = FirebaseVisionCloudDetectorOptions.Builder()
                .setModelType(FirebaseVisionCloudDetectorOptions.LATEST_MODEL)
                .setMaxResults(15)
                .build()
        // [END ml_build_cloud_vision_options]
    }

    fun enforceCertificateMatching() {
        // Dummy variable

        val myImage = FirebaseVisionImage.fromByteArray(byteArrayOf(),
                FirebaseVisionImageMetadata.Builder().build())

        // [START mlkit_certificate_matching]
        val optionsBuilder = FirebaseVisionCloudImageLabelerOptions.Builder()
        if (!BuildConfig.DEBUG) {
            // Requires physical, non-rooted device:
            optionsBuilder.enforceCertFingerprintMatch()
        }

        // Set other options. For example:
        optionsBuilder.setConfidenceThreshold(0.8f)
        // ...

        // And lastly:
        val options = optionsBuilder.build()
        FirebaseVision.getInstance().getCloudImageLabeler(options).processImage(myImage)
        // [END mlkit_certificate_matching]
    }


    private fun configureHostedModelSource() {
        // [START mlkit_cloud_model_source]
        val remoteModel = FirebaseCustomRemoteModel.Builder("foodClassifier").build()
        // [END mlkit_cloud_model_source]
    }

    private fun startModelDownloadTask(remoteModel: FirebaseCustomRemoteModel) {
        // [START mlkit_model_download_task]
        val conditions = FirebaseModelDownloadConditions.Builder()
                .requireWifi()
                .build()
        FirebaseModelManager.getInstance().download(remoteModel, conditions)
                .addOnCompleteListener {
                    // Success.
                }
        // [END mlkit_model_download_task]
    }

    private fun configureLocalModelSource() {
        // [START mlkit_local_model_source]
        val localModel = FirebaseCustomLocalModel.Builder()
                .setAssetFilePath("model.tflite")
                .build()
        // [END mlkit_local_model_source]
    }

    @Throws(FirebaseMLException::class)
    private fun createInterpreter(localModel: FirebaseCustomLocalModel): FirebaseModelInterpreter? {
        // [START mlkit_create_interpreter]
        val options = FirebaseModelInterpreterOptions.Builder(localModel).build()
        val interpreter = FirebaseModelInterpreter.getInstance(options)
        // [END mlkit_create_interpreter]

        return interpreter
    }

    private fun checkModelDownloadStatus(remoteModel: FirebaseCustomRemoteModel, localModel: FirebaseCustomLocalModel) {
        // [START mlkit_check_download_status]
        FirebaseModelManager.getInstance().isModelDownloaded(remoteModel)
                .addOnSuccessListener { isDownloaded ->
                    val options =
                            if (isDownloaded) {
                                FirebaseModelInterpreterOptions.Builder(remoteModel).build()
                            } else {
                                FirebaseModelInterpreterOptions.Builder(localModel).build()
                            }
                    val interpreter = FirebaseModelInterpreter.getInstance(options)
                }
        // [END mlkit_check_download_status]
    }

    private fun addDownloadListener(
            remoteModel: FirebaseCustomRemoteModel,
            conditions: FirebaseModelDownloadConditions
    ) {
        // [START mlkit_remote_model_download_listener]
        FirebaseModelManager.getInstance().download(remoteModel, conditions)
                .addOnCompleteListener {
                    // Download complete. Depending on your app, you could enable the ML
                    // feature, or switch from the local model to the remote model, etc.
                }
        // [END mlkit_remote_model_download_listener]
    }

    @Throws(FirebaseMLException::class)
    private fun createInputOutputOptions(): FirebaseModelInputOutputOptions {
        // [START mlkit_create_io_options]
        val inputOutputOptions = FirebaseModelInputOutputOptions.Builder()
                .setInputFormat(0, FirebaseModelDataType.FLOAT32, intArrayOf(1, 224, 224, 3))
                .setOutputFormat(0, FirebaseModelDataType.FLOAT32, intArrayOf(1, 5))
                .build()
        // [END mlkit_create_io_options]
        return inputOutputOptions
    }

    // 이거 위에 가져가서 썼음
    private fun bitmapToInputArray(): Array<Array<Array<FloatArray>>> {
        // [START mlkit_bitmap_input]
        val bitmap = Bitmap.createScaledBitmap(yourInputImage, 224, 224, true)

        val batchNum = 0
        val input = Array(1) { Array(224) { Array(224) { FloatArray(3) } } }
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
        // [END mlkit_bitmap_input]
        return input
    }

    @Throws(FirebaseMLException::class)
    private fun runInference() {
        val localModel = FirebaseCustomLocalModel.Builder().build()
        val firebaseInterpreter = createInterpreter(localModel)!!
        val input = bitmapToInputArray()
        val inputOutputOptions = createInputOutputOptions()

        // [START mlkit_run_inference]
        val inputs = FirebaseModelInputs.Builder()
                .add(input) // add() as many input arrays as your model requires
                .build()
        firebaseInterpreter.run(inputs, inputOutputOptions)
                .addOnSuccessListener { result ->
                    // [START_EXCLUDE]
                    // [START mlkit_read_result]
                    val output = result.getOutput<Array<FloatArray>>(0)
                    val probabilities = output[0]
                    // [END mlkit_read_result]
                    // [END_EXCLUDE]
                }
                .addOnFailureListener { e ->
                    // Task failed with an exception
                    // ...
                }
        // [END mlkit_run_inference]
    }

    @Throws(IOException::class)
    private fun useInferenceResult(probabilities: FloatArray) {
        // [START mlkit_use_inference_result]
        val reader = BufferedReader(
                InputStreamReader(assets.open("retrained_labels.txt")))
        for (i in probabilities.indices) {
            val label = reader.readLine()
            Log.i("MLKit", String.format("%s: %1.4f", label, probabilities[i]))
        }
        // [END mlkit_use_inference_result]
    }

}
