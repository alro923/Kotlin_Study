package com.google.firebase.example.mlkit.kotlin

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
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
import java.io.*
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.*


class MainActivity : AppCompatActivity() {
    var FROM_ALBUM = 1    // onActivityResult 식별자
    var FROM_CAMERA = 2   // 카메라는 사용 안함

    // private val yourInputImage: Bitmap get() = Bitmap.createBitmap(0, 0, Bitmap.Config.ALPHA_8)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        // Get the bitmap from assets and display into image view
        val bitmap = assetsToBitmap("sample.jpeg")
        // If bitmap is not null
        bitmap?.let {
            imageView.setImageBitmap(bitmap)
        }


        // 결과값 출력하는 TextView
        val tv_output = findViewById<TextView>(R.id.textView)

        // simple_1.tflite 모델 사용하는 onClick, 잘된다!
        findViewById<View>(R.id.button_1).setOnClickListener {
            val input = intArrayOf(3)
            val output = intArrayOf(0)

            val tflite: Interpreter = getTfliteInterpreter("simple_1.tflite")!!
            tflite.run(input, output)
            tv_output.text = output[0].toString()
        }

        button_2.setOnClickListener {
            val intent = Intent()
            intent.type = "image/*"
            intent.action = Intent.ACTION_GET_CONTENT
            startActivityForResult(intent, FROM_ALBUM)
        }

        findViewById<View>(R.id.button_3).setOnClickListener {

            if(bitmap!=null){
                // Save the bitmap to a file and display it into image view
                val uri = bitmapToFile(bitmap)
                imageView.setImageURI(uri)

                // Display the saved bitmap's uri in text view
                textView.text = uri.toString()

                // Show a toast message
                toast("Bitmap saved in a file.")
            }else{
                toast("bitmap not found.")
            }

        }

    }
    override fun onActivityResult(requestCode : Int, resultCode : Int, data : Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != FROM_ALBUM || resultCode != RESULT_OK)
            return

        try {
            // 선택한 이미지에서 비트맵 생성
            // var stream = ByteArrayInputStream()
            val stream = data?.data?.let { contentResolver.openInputStream(it) }
            val bmp = BitmapFactory.decodeStream(stream)

            if (stream != null) {
                stream.close()
            }

            imageView.setImageBitmap(bmp)

        }
        catch (e:IOException){
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
            val stream:OutputStream = FileOutputStream(file)
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
}



