package com.google.firebase.example.mlkit.kotlin.customview

import com.google.firebase.example.mlkit.kotlin.tflite.Classifier

interface ResultsView {
    fun setResults(results: List<Classifier.Recognition?>?)
}