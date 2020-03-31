package com.carlosgub.qrscanner

import android.graphics.Bitmap
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcode
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetectorOptions
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

class QRUtil {

    private var listener:Listener?=null

    interface Listener{
        fun onSuccess(barcodeValue:String)
        fun onError(error:String?)
        fun nextImage()
    }

    fun setListener(listener:Listener){
        this.listener = listener
    }

    fun getQRCodeDetails(bitmap: Bitmap) {
        val options = FirebaseVisionBarcodeDetectorOptions.Builder()
            .setBarcodeFormats(
                FirebaseVisionBarcode.FORMAT_ALL_FORMATS)
            .build()

        /**
         * Exiten estos formatos
         *  Code 128 (FORMAT_CODE_128)
            Code 39 (FORMAT_CODE_39)
            Code 93 (FORMAT_CODE_93)
            Codabar (FORMAT_CODABAR)
            EAN-13 (FORMAT_EAN_13)
            EAN-8 (FORMAT_EAN_8)
            ITF (FORMAT_ITF)
            UPC-A (FORMAT_UPC_A)
            UPC-E (FORMAT_UPC_E)
            QR Code (FORMAT_QR_CODE)
            PDF417 (FORMAT_PDF417)
            Aztec (FORMAT_AZTEC)
            Data Matrix (FORMAT_DATA_MATRIX)
         */
        val detector = FirebaseVision.getInstance().getVisionBarcodeDetector(options)
        val image = FirebaseVisionImage.fromBitmap(bitmap)
        detector.detectInImage(image)
            .addOnSuccessListener {
                if(it.isNotEmpty()){
                    for (firebaseBarcode in it) {
                        listener?.onSuccess(firebaseBarcode.displayValue?:"")  //Display contents inside the barcode
                    }
                }else{
                    listener?.nextImage()
                    detector.close()
                }

            }
            .addOnFailureListener {
                it.printStackTrace()
                listener?.onError(it.message)
            }
    }
}