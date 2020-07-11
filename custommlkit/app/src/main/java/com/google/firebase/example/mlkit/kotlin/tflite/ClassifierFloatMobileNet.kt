package com.google.firebase.example.mlkit.kotlin.tflite

import android.app.Activity
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.TensorOperator
import org.tensorflow.lite.support.common.ops.NormalizeOp


/** This TensorFlowLite classifier works with the float MobileNet model.  */
class ClassifierFloatMobileNet
/**
 * Initializes a `ClassifierFloatMobileNet`.
 *
 * @param activity
 */
(activity: Activity?, device: Device?, numThreads: Int) : Classifier(activity, device, numThreads) {
    override val modelPath: String
        protected get() = "model.tflite"

    override val labelPath: String
        protected get() = "labels.txt"

    override val preprocessNormalizeOp: TensorOperator
        protected get() = NormalizeOp(IMAGE_MEAN, IMAGE_STD)

    override val postprocessNormalizeOp: TensorOperator
        protected get() = NormalizeOp(PROBABILITY_MEAN, PROBABILITY_STD)

    companion object {
        /** Float MobileNet requires additional normalization of the used input.  */
        private const val IMAGE_MEAN = 127.5f
        private const val IMAGE_STD = 127.5f

        /**
         * Float model does not need dequantization in the post-processing. Setting mean and std as 0.0f
         * and 1.0f, repectively, to bypass the normalization.
         */
        private const val PROBABILITY_MEAN = 0.0f
        private const val PROBABILITY_STD = 1.0f
    }
}