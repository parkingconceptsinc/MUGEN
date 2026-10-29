package com.parkingconcepts.mortimer

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import java.io.File

internal class WaveExportState {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var pending:File?=null
    lateinit var save:(File,String)->Unit
}
@Composable internal fun rememberWaveExporter():WaveExportState {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val state=remember{WaveExportState()}
    var pendingPath by rememberSaveable{mutableStateOf<String?>(null)}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")){uri->
        val file=state.pending?:pendingPath?.let{File(it)};state.pending=null;pendingPath=null
        fun cleanTemporary(){if(file!=null&&file.parentFile?.canonicalFile==context.cacheDir.canonicalFile&&(file.name.startsWith("voice-render-")||file.name.startsWith("synth-render-")))file.delete()}
        if(uri!=null&&file!=null){state.busy=true;scope.launch{
            try{withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri,"wt")?.use{output->file.inputStream().use{it.copyLimitedTo(output,MAX_CACHE_BYTES)}}?:error("Could not open the destination.")};state.error=null;cleanTemporary()}
            catch(cancelled:CancellationException){throw cancelled}
            catch(failure:Exception){state.error=failure.message?:"Could not save WAV."}
            finally{state.busy=false}
        }} else {state.busy=false;cleanTemporary()}
    }
    state.save={file,name->if(file.isFile){state.pending=file;pendingPath=file.absolutePath;state.busy=true;runCatching{launcher.launch(name)}.onFailure{state.busy=false;state.error="No document picker is available to save this WAV."}}else state.error="Render this WAV again before saving."}
    return state
}
