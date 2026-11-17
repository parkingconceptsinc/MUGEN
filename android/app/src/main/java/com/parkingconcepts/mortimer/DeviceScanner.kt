package com.parkingconcepts.mortimer

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File
import java.util.ArrayDeque

internal data class DeviceScanProgress(val visited:Int=0,val found:Int=0,val duplicates:Int=0,val unreadable:Int=0,val path:String="")
internal data class DeviceScanResult(val items:List<LibraryItem>,val progress:DeviceScanProgress,val partial:Boolean,val roots:Int)
internal fun deviceScanAllowed(context:Context)=if(Build.VERSION.SDK_INT>=30) Environment.isExternalStorageManager() else
    androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.READ_EXTERNAL_STORAGE)==android.content.pm.PackageManager.PERMISSION_GRANTED
internal fun sharedStorageRoots(context:Context):List<File>{
    val primary=Environment.getExternalStorageDirectory()
    val volumes=if(Build.VERSION.SDK_INT>=30)(context.getSystemService(Context.STORAGE_SERVICE) as StorageManager).storageVolumes.mapNotNull { it.directory } else
        context.getExternalFilesDirs(null).mapNotNull { it?.parentFile?.parentFile?.parentFile?.parentFile }
    return (listOf(primary)+volumes).filter { it.isDirectory }.distinctBy { runCatching{it.canonicalPath}.getOrDefault(it.absolutePath) }
}
internal fun scannedFileIdentity(uri:Uri,roots:List<File>,ownAuthority:String?=null):String?=runCatching {
    when{
        uri.scheme=="file"->File(requireNotNull(uri.path)).canonicalPath
        ownAuthority!=null&&uri.authority==ownAuthority&&uri.path?.startsWith("/device-storage/")==true->File("/storage",uri.path!!.removePrefix("/device-storage/")).canonicalPath
        uri.authority=="com.android.externalstorage.documents"->{
            val id=DocumentsContract.getDocumentId(uri);val volume=id.substringBefore(':');val relative=id.substringAfter(':',"")
            val root=if(volume=="primary")roots.firstOrNull() else roots.firstOrNull { it.name==volume }
            root?.let{File(it,relative).canonicalPath}
        }
        else->classifyLibraryFile(file.name)?.let { it.kind to it.format }
    }
}.getOrNull()
internal fun scanKind(file:File):Pair<String,String>? {
    val extension=file.extension.lowercase(java.util.Locale.ROOT)
    if(extension in AUDIO_EXTENSIONS&&extension in VIDEO_EXTENSIONS){
        val reader=android.media.MediaMetadataRetriever()
        return try{file.inputStream().use{reader.setDataSource(it.fd)};if(reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)=="yes")"videos" to "video" else "audio" to "audio"}
        catch(_:Exception){"videos" to "video"}finally{reader.release()}
    }
    return when{
        extension=="zip"->classifyZipLibraryFile(file)?.let { it.kind to it.format }
        else->classifyLibraryFile(file.name)?.let { it.kind to it.format }
    }
}
/** Enumerate shared storage only. Canonical containment and visited sets stop symlinks escaping roots/looping. */
internal suspend fun scanDeviceFiles(context:Context,existing:List<LibraryItem>,roots:List<File> = sharedStorageRoots(context),
    maxEntries:Int=250_000,maxResults:Int=25_000,uriForFile:(File)->Uri={FileProvider.getUriForFile(context,context.packageName+".fileprovider",it)},
    onProgress:(DeviceScanProgress)->Unit={}):DeviceScanResult=withContext(Dispatchers.IO){
    require(maxEntries>0&&maxResults>0)
    val canonicalRoots=roots.mapNotNull {runCatching{it.canonicalFile}.getOrNull()}.distinctBy{it.path}
    require(canonicalRoots.isNotEmpty()){ "No readable shared-storage volumes were found." }
    val knownUris=existing.mapTo(HashSet()){it.uri};val knownPaths=existing.mapNotNullTo(HashSet()){scannedFileIdentity(it.androidUri,canonicalRoots,context.packageName+".fileprovider")}
    val visitedPaths=HashSet<String>();val candidates=ArrayList<LibraryItem>();val stack=ArrayDeque<Pair<File,Int>>()
    canonicalRoots.reversed().forEach{stack.addLast(it to 0)}
    var visited=0;var duplicates=0;var unreadable=0;var partial=false;var lastTick=0L;var lastPath=""
    while(stack.isNotEmpty()){
        currentCoroutineContext().ensureActive()
        if(visited>=maxEntries||candidates.size>=maxResults){partial=true;break}
        val (file,depth)=stack.removeLast();val canonical=runCatching{file.canonicalFile}.getOrNull()?:continue
        if(canonicalRoots.none{canonical.path==it.path||canonical.path.startsWith(it.path+File.separator)})continue
        if(!visitedPaths.add(canonical.path))continue
        visited++;lastPath=canonical.name
        if(canonical.isDirectory){
            if(depth>=64){partial=true;continue}
            if(canonical.name in setOf(".thumbnails",".Trash",".trash")||canonicalRoots.any { root->canonical.path==File(root,"Android/data").path||canonical.path==File(root,"Android/obb").path })continue
            val children=runCatching{canonical.listFiles()}.getOrNull()
            if(children==null){unreadable++;continue}
            children.reversed().forEach{stack.addLast(it to depth+1)}
        }else if(canonical.isFile&&canonical.length()>0){
            if(!canonical.canRead()){unreadable++;continue}
            val kind=scanKind(canonical)
            if(kind!=null){
                if(canonical.path in knownPaths){duplicates++}else{
                    val uri=runCatching{uriForFile(canonical)}.getOrNull()
                    if(uri==null){unreadable++}else if(uri.toString() in knownUris){duplicates++}else{
                        var item=LibraryItem(uri.toString(),canonical.name,kind.first,kind.second,canonical.parentFile?.name.orEmpty(),size=canonical.length())
                        if(kind.first=="audio")item=withAudioTags(context,item)
                        candidates.add(item);knownPaths.add(canonical.path);knownUris.add(uri.toString())
                    }
                }
            }
        }
        val now=System.currentTimeMillis();if(now-lastTick>150){onProgress(DeviceScanProgress(visited,candidates.size,duplicates,unreadable,lastPath));lastTick=now}
    }
    val progress=DeviceScanProgress(visited,candidates.size,duplicates,unreadable,lastPath);onProgress(progress)
    DeviceScanResult(candidates,progress,partial||unreadable>0,canonicalRoots.size)
}
