package com.parkingconcepts.mortimer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*

@Composable
internal fun DeviceScanDialog(library:List<LibraryItem>,onImport:suspend(List<LibraryItem>)->Unit,onClose:()->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope();val lifecycle=LocalLifecycleOwner.current.lifecycle
    var allowed by remember{mutableStateOf(deviceScanAllowed(context))};var progress by remember{mutableStateOf(DeviceScanProgress())}
    var result by remember{mutableStateOf<DeviceScanResult?>(null)};var job by remember{mutableStateOf<Job?>(null)};var error by remember{mutableStateOf<String?>(null)}
    var importing by remember{mutableStateOf(false)};var categories by remember{mutableStateOf(setOf("audio","videos","books","comics"))}
    fun scan(){if(job?.isActive==true)return;result=null;error=null;progress=DeviceScanProgress();job=scope.launch{
        try{result=scanDeviceFiles(context,library,onProgress={value->scope.launch{progress=value}})}
        catch(cancelled:CancellationException){error="Scan cancelled. No files were imported.";throw cancelled}
        catch(failure:Exception){error=failure.message?:"Could not scan shared storage."}
        finally{job=null}
    }}
    val access=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){allowed=deviceScanAllowed(context);if(allowed)scan()}
    val legacy=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->allowed=granted;if(granted)scan()}
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)allowed=deviceScanAllowed(context)};lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer);job?.cancel()}}
    val selected=result?.items?.filter{it.kind in categories}.orEmpty()
    AlertDialog(onDismissRequest={if(!importing){job?.cancel();onClose()}},title={Text("Scan device")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("Find music, videos, EPUB/PDF/text books and comic archives in shared storage and mounted SD cards. Originals and reading progress are preserved.")
        if(!allowed)Text("Allow shared-storage access in Android to scan all folders. Android keeps private app folders protected.")
        if(job?.isActive==true){LinearProgressIndicator(Modifier.fillMaxWidth());Text("${progress.visited} entries checked · ${progress.found} new files · ${progress.duplicates} already in library");Text(progress.path,style=MaterialTheme.typography.bodySmall)}
        result?.let{r->
            Text("${r.items.size} new files · ${r.progress.duplicates} duplicates skipped · ${r.roots} storage volumes")
            if(r.partial)Text("Partial scan: ${r.progress.unreadable} folders could not be read, or a scan limit was reached. Only the files listed below will be added.",color=MaterialTheme.colorScheme.error)
            listOf("audio" to "Music","videos" to "Videos","books" to "Books","comics" to "Comics").forEach{(kind,label)->
                Row{Checkbox(kind in categories,onCheckedChange={categories=if(it)categories+kind else categories-kind});Text("$label · ${r.items.count{it.kind==kind}}",Modifier.padding(top=12.dp))}
            }
            Text("ZIP archives are included only when recognized as EPUB or image-only comics. Photos remain available in Photos.",style=MaterialTheme.typography.bodySmall)
        }
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        if(importing)LinearProgressIndicator(Modifier.fillMaxWidth())
    }},confirmButton={
        if(result!=null)TextButton(enabled=selected.isNotEmpty()&&!importing,onClick={importing=true;scope.launch{try{onImport(selected);onClose()}catch(failure:Exception){error=failure.message?:"Could not import scanned files."}finally{importing=false}}}){Text("Import ${selected.size} files")}
        else TextButton(enabled=job?.isActive!=true,onClick={if(allowed)scan() else if(Build.VERSION.SDK_INT>=30){
            val intent=Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:${context.packageName}"))
            runCatching{access.launch(intent)}.onFailure{runCatching{access.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))}.onFailure{error="Open Android Settings → Special app access → All files access, then enable MUGEN."}}
        }else legacy.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)}){Text(if(allowed)"Start scan" else "Allow device scan")}
    },dismissButton={TextButton(enabled=!importing,onClick={if(job?.isActive==true){job?.cancel()}else onClose()}){Text(if(job?.isActive==true)"Cancel scan" else "Close")}})
}
internal fun mergeDeviceScan(context:android.content.Context,items:List<LibraryItem>):List<LibraryItem>{
    val roots=sharedStorageRoots(context)
    return LibraryStore.update(context){current->
        val uris=current.mapTo(HashSet()){it.uri};val paths=current.mapNotNullTo(HashSet()){scannedFileIdentity(it.androidUri,roots,context.packageName+".fileprovider")}
        current+items.filter{item->val path=scannedFileIdentity(item.androidUri,roots,context.packageName+".fileprovider");val fresh=item.uri !in uris&&(path==null||path !in paths);if(fresh){uris.add(item.uri);path?.let(paths::add)};fresh}
    }
}

@Composable
internal fun DeviceScanAccessNotice(library:List<LibraryItem>,resumeTick:Int){
    val context=LocalContext.current
    var missing by remember{mutableStateOf(false)}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){missing=!deviceScanAllowed(context)}
    val legacy=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){missing=!it}
    LaunchedEffect(library,resumeTick){missing=library.any{it.androidUri.authority==context.packageName+".fileprovider"&&it.androidUri.path?.startsWith("/device-storage/")==true}&&!deviceScanAllowed(context)}
    if(missing)Row(Modifier.fillMaxWidth()){
        Text("Device-scan files need shared-storage access",modifier=Modifier.weight(1f).padding(top=12.dp),style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={if(Build.VERSION.SDK_INT>=30)runCatching{launcher.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:${context.packageName}")))}.onFailure{launcher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))} else legacy.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)}){Text("Restore access")}
    }
}
