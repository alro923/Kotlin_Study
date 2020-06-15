package com.uhhyunjoo.cloneinsta.navigation.util

import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import com.uhhyunjoo.cloneinsta.navigation.model.PushDTO
import okhttp3.*
import java.io.IOException

class FcmPush{
    var JSON = MediaType.parse("application/json; charset=utf-8")
    var url = "https://fcm.googleapis.com/fcm/send"
    var serverKey = "AAAAov2_bqU:APA91bELl6xDlZfJHrUMJOXIPbWqGSL5y8GWOj3goluIpYELt6SAQ4YbuG_ycgD3-zpmvGdJZP1VPjt1JMCRi7QGJxX-Z3Hdc_asYPZS5fGF-CqepLYCZQ4b4xsaU3vrgQ52ojkjljjf"
    // can't find legacy server key
    var gson : Gson? = null
    var okHttpClients : OkHttpClient? = null

    companion object{
        var instance = FcmPush()
    }
    init{
        gson = Gson()
        okHttpClients = OkHttpClient()
    }
    fun sendMessage(destinationUid : String, title : String, message : String){
        FirebaseFirestore.getInstance().collection("pushtokens").document(destinationUid).get().addOnCompleteListener {
            task->
            if(task.isSuccessful){
                var token = task?.result?.get("pushToken").toString()

                var pushDTO = PushDTO()
                pushDTO.to = token
                pushDTO.notification.title = title
                pushDTO.notification.body = message

                var body = RequestBody.create(JSON, gson?.toJson(pushDTO))
                var request = Request.Builder()
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "key="+serverKey)
                    .url(url)
                    .post(body)
                    .build()

                okHttpClients?.newCall(request)?.enqueue(object : Callback {
                    override fun onFailure(call: Call?, e: IOException?) {
                        // 실패
                    }

                    override fun onResponse(call: Call?, response: Response?) {
                         // 성공 시 메세지 띄워줌
                        println(response?.body()?.string())
                    }

                })
            }
        }
    }
}