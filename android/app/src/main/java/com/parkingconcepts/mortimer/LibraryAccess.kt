package com.parkingconcepts.mortimer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class LibraryFolderAccess(val tree: Uri, val sample: Uri, val name: String)

internal fun libraryFolderFor(uri: Uri): LibraryFolderAccess? = runCatching {
    if (uri.scheme != "content" || !DocumentsContract.isTreeUri(uri)) return null
    val id = DocumentsContract.getTreeDocumentId(uri)
    LibraryFolderAccess(DocumentsContract.buildTreeDocumentUri(uri.authority, id), uri,
        id.substringAfter(':').substringAfterLast('/').ifBlank { "Internal storage" })
}.getOrNull()

internal fun hasLibraryUriAccess(context: Context, uri: Uri): Boolean =
    context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED

internal fun missingLibraryFolders(context: Context, uris: List<Uri>): List<LibraryFolderAccess> = uris
    .mapNotNull(::libraryFolderFor).distinctBy { it.tree.toString() }.filterNot { hasLibraryUriAccess(context, it.sample) }

internal fun reconnectLibraryFolder(context: Context, expected: LibraryFolderAccess, selected: Uri) {
    require(selected.authority == expected.tree.authority &&
        DocumentsContract.getTreeDocumentId(selected) == DocumentsContract.getTreeDocumentId(expected.tree)) {
        "Select the original folder: ${expected.name}. Your library has not been changed."
    }
    context.contentResolver.takePersistableUriPermission(selected, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    check(hasLibraryUriAccess(context, expected.sample)) { "Folder access was not granted. Choose ${expected.name} again." }
}

@Composable
internal fun ReconnectFoldersDialog(folders: List<LibraryFolderAccess>, onRestored: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<LibraryFolderAccess?>(null) }
    var completed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var message by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val expected = selected
        if (uri != null && expected != null) {
            try {
                reconnectLibraryFolder(context, expected, uri)
                completed = completed + expected.tree.toString(); message = null; onRestored()
                if (folders.all { it.tree.toString() in completed }) onClose()
            } catch (failure: Exception) { message = failure.message ?: "Could not reconnect this folder." }
        }
        selected = null
    }
    AlertDialog(onDismissRequest = onClose, title = { Text("Reconnect folders") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Android needs permission to read these saved folders again. Select each folder and tap Use this folder. Your library and reading progress stay intact.")
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(folders, key = { it.tree.toString() }) { folder ->
                    TextButton(enabled = folder.tree.toString() !in completed, onClick = {
                        selected = folder
                        picker.launch(DocumentsContract.buildDocumentUriUsingTree(folder.tree, DocumentsContract.getTreeDocumentId(folder.tree)))
                    }) { Text(if (folder.tree.toString() in completed) "✓ ${folder.name}" else "Reconnect ${folder.name}") }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Close") } })
}

@Composable
internal fun LibraryAccessNotice(library: List<LibraryItem>, resumeTick: Int) {
    val context = LocalContext.current
    var missing by remember { mutableStateOf<List<LibraryFolderAccess>>(emptyList()) }
    var show by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(library, resumeTick, refresh) {
        missing = withContext(Dispatchers.IO) { missingLibraryFolders(context, library.map { it.androidUri }) }
    }
    if (missing.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${missing.size} saved folders need access", modifier = Modifier.weight(1f).padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { show = true }) { Text("Reconnect folders") }
    }
    // Keep this request stable while a picker is open; refresh its list when the dialog closes.
    if (show) {
        val requests = remember { missing }
        ReconnectFoldersDialog(requests, onRestored = {}, onClose = { show = false; refresh++ })
    }
}
