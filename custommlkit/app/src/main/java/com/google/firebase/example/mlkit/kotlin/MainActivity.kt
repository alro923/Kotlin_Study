package com.google.firebase.example.mlkit.kotlin

import android.app.Activity
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
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
import java.io.*
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel


class MainActivity : AppCompatActivity() {
    var FROM_ALBUM = 1    // onActivityResult 식별자
    var FROM_CAMERA = 2   // 카메라는 사용 안함

    // private val yourInputImage: Bitmap get() = Bitmap.createBitmap(0, 0, Bitmap.Config.ALPHA_8)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        // 결과값 출력하는 TextView
        val tv_output = findViewById<TextView>(R.id.tv_output)

        // simple_1.tflite 모델 사용하는 onClick, 잘된다!
//        findViewById<View>(R.id.button_1).setOnClickListener {
//            val input = intArrayOf(3)
//            val output = intArrayOf(0)
//
//            val tflite: Interpreter = getTfliteInterpreter("simple_1.tflite")!!
//            tflite.run(input, output)
//            tv_output.text = output[0].toString()
//        }

        button_2.setOnClickListener {
            val intent = Intent()
            intent.type = "image/*"
            intent.action = Intent.ACTION_GET_CONTENT
            startActivityForResult(intent, FROM_ALBUM)
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

            imageResult.setImageBitmap(bmp)

        }
        catch (e:IOException){
            e.printStackTrace()
        }
    }

}



