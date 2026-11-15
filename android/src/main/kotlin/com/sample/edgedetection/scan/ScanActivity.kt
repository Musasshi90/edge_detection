package com.sample.edgedetection.scan

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.*
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.exifinterface.media.ExifInterface
import com.sample.edgedetection.ERROR_CODE
import com.sample.edgedetection.EdgeDetectionHandler
import com.sample.edgedetection.R
import com.sample.edgedetection.REQUEST_CODE
import com.sample.edgedetection.base.BaseActivity
import com.sample.edgedetection.view.PaperRectangle
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.io.IOException

class ScanActivity : BaseActivity(), IScanView.Proxy {

    private lateinit var mPresenter: ScanPresenter

    override fun provideContentViewId(): Int = R.layout.activity_scan

    override fun initPresenter() {
        val initialBundle = intent.getBundleExtra(EdgeDetectionHandler.INITIAL_BUNDLE) as Bundle
        mPresenter = ScanPresenter(this, this, initialBundle)
    }

    override fun prepare() {
        if (!OpenCVLoader.initLocal()) {
            Log.i(TAG, "loading opencv error, exit")
            finish()
        }
        else {
            Log.i("OpenCV", "OpenCV loaded Successfully!");
        }

        findViewById<View>(R.id.shut).setOnClickListener {
            if (mPresenter.canShut) {
                mPresenter.shut()
            }
        }

        // to hide the flashLight button from  SDK versions which we do not handle the permission for!
        findViewById<View>(R.id.flash).visibility = if
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU && baseContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH))
            View.VISIBLE else
                View.GONE

        findViewById<View>(R.id.flash).setOnClickListener {
            mPresenter.toggleFlash()
        }

        val initialBundle = intent.getBundleExtra(EdgeDetectionHandler.INITIAL_BUNDLE) as Bundle

        if(!initialBundle.containsKey(EdgeDetectionHandler.FROM_GALLERY)){
            this.title = initialBundle.getString(EdgeDetectionHandler.SCAN_TITLE, "") as String
        }

//        findViewById<View>(R.id.gallery).visibility =
//                if (initialBundle.getBoolean(EdgeDetectionHandler.CAN_USE_GALLERY, true))
//                    View.VISIBLE
//                else View.GONE

//        findViewById<View>(R.id.gallery).setOnClickListener {
//            pickupFromGallery(false,initialBundle)
//        }

        if (initialBundle.containsKey(EdgeDetectionHandler.FROM_GALLERY) && initialBundle.getBoolean(EdgeDetectionHandler.FROM_GALLERY,false))
        {
            pickupFromGallery(true,initialBundle)
        }
    }

    private fun pickupFromGallery(isGallery:Boolean,initialBundle:Bundle ) {
        mPresenter.stop()
        if(isGallery){
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                var imagePath:String = initialBundle.getString(EdgeDetectionHandler.SAVE_TO,"")
                val imageFile = File(imagePath)
                val imageUri = Uri.fromFile(imageFile)
                onImageSelected(imageUri)
            }
        }else{
            val gallery = Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply{type="image/*"}
            ActivityCompat.startActivityForResult(this, gallery, 1, null)
        }
    }

    override fun onStart() {
        super.onStart()
        mPresenter.start()
    }

    override fun onStop() {
        super.onStop()
        mPresenter.stop()
    }

    override fun exit() {
        finish()
    }

    override fun getCurrentDisplay(): Display? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            this.display
        } else {
            this.windowManager.defaultDisplay
        }
    }

    override fun getSurfaceView() = findViewById<SurfaceView>(R.id.surface)

    override fun getPaperRect() = findViewById<PaperRectangle>(R.id.paper_rect)

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                setResult(Activity.RESULT_OK)
                Log.i(TAG, "setResult(Activity.RESULT_OK) , setResult(Activity.RESULT_OK)")
                finish()
            } else {
                if (intent.hasExtra(EdgeDetectionHandler.FROM_GALLERY) && intent.getBooleanExtra(EdgeDetectionHandler.FROM_GALLERY, false))
                    finish()
            }
        }

        if (requestCode == 1) {
            if (resultCode == Activity.RESULT_OK) {
                val uri: Uri = data!!.data!!
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    onImageSelected(uri)
                }
            }else if(resultCode == Activity.RESULT_CANCELED){
                mPresenter.start()
            }
            else {
                if (intent.hasExtra(EdgeDetectionHandler.FROM_GALLERY) && intent.getBooleanExtra(EdgeDetectionHandler.FROM_GALLERY,false))
                    finish()
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            onBackPressedDispatcher.onBackPressed()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun onImageSelected(imageUri: Uri) {
        try {
            var rotation = -1
            val orientation = contentResolver.openInputStream(imageUri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED
                )
            } ?: throw IOException("Could not open selected image")
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotation = Core.ROTATE_90_CLOCKWISE
                ExifInterface.ORIENTATION_ROTATE_180 -> rotation = Core.ROTATE_180
                ExifInterface.ORIENTATION_ROTATE_270 -> rotation = Core.ROTATE_90_COUNTERCLOCKWISE
            }
            val inputData = contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
                ?: throw IOException("Could not read selected image")
            if (inputData.isEmpty()) throw IOException("Selected image is empty")
            val mat = Mat(1, inputData.size, CvType.CV_8UC1)
            val pic = try {
                mat.put(0, 0, inputData)
                Imgcodecs.imdecode(mat, Imgcodecs.IMREAD_UNCHANGED)
            } finally {
                mat.release()
            }
            if (pic.empty()) {
                pic.release()
                throw IOException("Selected image could not be decoded")
            }
            if (rotation > -1) Core.rotate(pic, pic, rotation)

            mPresenter.detectEdge(pic)
        } catch (error: Exception) {
            val intent = Intent()
            intent.putExtra("RESULT", error.toString())
            setResult(ERROR_CODE, intent)
            finish()
        }

    }

}
