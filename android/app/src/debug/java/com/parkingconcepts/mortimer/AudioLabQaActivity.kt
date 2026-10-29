package com.parkingconcepts.mortimer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

/** Isolated settings, synthetic voice and muted preview on a dedicated secondary display only. */
class AudioLabQaActivity : ComponentActivity() {
    companion object {
        @Volatile internal var current=java.lang.ref.WeakReference<AudioLabQaActivity>(null)
        @Volatile internal var destroyed=java.util.concurrent.CountDownLatch(0)
    }
    override fun onConfigurationChanged(configuration:android.content.res.Configuration){
        super.onConfigurationChanged(configuration)
        if(android.os.Build.VERSION.SDK_INT>=30&&display?.displayId!=intent.getIntExtra("qa_display",-1))finish()
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        if(android.os.Build.VERSION.SDK_INT<30||display?.displayId!=intent.getIntExtra("qa_display",-1))return finish()
        current=java.lang.ref.WeakReference(this);destroyed=java.util.concurrent.CountDownLatch(1)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window,false)
        androidx.core.view.WindowInsetsControllerCompat(window,window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        val fixture=intent.getStringExtra("fixture")?.let{java.io.File(it)}
        setContent{MortimerTheme("dark"){
            if(intent.getBooleanExtra("scan_mode",false))DeviceScanDialog(emptyList(),onImport={},onClose={finish()})
            else EqualizerSection(Modifier.fillMaxSize(),persistSettings=false,qaTake=fixture)
        }}
    }
    override fun onDestroy(){super.onDestroy();if(current.get()===this){current.clear();destroyed.countDown()}}
}
