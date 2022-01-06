package com.example.okhtt_okgo

import android.app.Application
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import com.example.okhtt_okgo.R
import com.lzy.okgo.OkGo
import com.lzy.okserver.OkDownload
import java.io.File
import java.lang.Exception

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btn)
                .setOnClickListener {


                    val file = File(applicationContext.getExternalFilesDir(null),"dddd.txt")

                    if (file.exists().not()){
                        file.createNewFile()
                    }
                    try {
                        OkGo.getInstance().init(application).retryCount = 0
                        OkGo.REFRESH_TIME = 68
                        OkDownload.getInstance().threadPool.setCorePoolSize(3)
                        OkDownload.getInstance().threadPool.setCorePoolSize(3)
                    }catch (e:Exception){
                        Log.e("lmk",e.localizedMessage)
                    }
                }

    }
}