package com.uhhyunjoo.cloneinsta.navigation.model

import android.app.Notification

data class PushDTO(
    var to : String? = null, // push 받는 사람의 tokenID
    var notification : Notification = Notification()

){
    data class Notification(
        var body : String?= null,
        var title : String? = null
    )
}