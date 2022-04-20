package sushi.hardcore.droidfs.file_operations

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import sushi.hardcore.droidfs.GocryptfsVolume
import sushi.hardcore.droidfs.R
import sushi.hardcore.droidfs.explorers.ExplorerElement
import sushi.hardcore.droidfs.util.PathUtils
import sushi.hardcore.droidfs.util.Wiper
import java.io.File
import java.io.FileNotFoundException

class FileOperationService : Service() {
    companion object {
        const val NOTIFICATION_CHANNEL_ID = "FileOperations"
        const val ACTION_CANCEL = "file_operation_cancel"
    }

    private val binder = LocalBinder()
    private lateinit var gocryptfsVolume: GocryptfsVolume
    private lateinit var notificationManager: NotificationManagerCompat
    private val tasks = HashMap<Int, Job>()
    private var lastNotificationId = 0

    inner class LocalBinder : Binder() {
        fun getService(): FileOperationService = this@FileOperationService
        fun setGocryptfsVolume(g: GocryptfsVolume) {
            gocryptfsVolume = g
        }
    }

    override fun onBind(p0: Intent?): IBinder {
        return binder
    }

    private fun showNotification(message: Int, total: Int?): FileOperationNotification {
        ++lastNotificationId
        if (!::notificationManager.isInitialized){
            notificationManager = NotificationManagerCompat.from(this)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.file_operations),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        notificationBuilder
                .setContentTitle(getString(message))
                .setSmallIcon(R.mipmap.icon_launcher)
                .setOngoing(true)
                .addAction(NotificationCompat.Action(
                    R.drawable.icon_close,
                    getString(R.string.cancel),
                    PendingIntent.getBroadcast(
                        this,
                        0,
                        Intent(this, NotificationBroadcastReceiver::class.java).apply {
                            val bundle = Bundle()
                            bundle.putBinder("binder", LocalBinder())
                            bundle.putInt("notificationId", lastNotificationId)
                            putExtra("bundle", bundle)
                            action = ACTION_CANCEL
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT
                    )
                ))
        if (total != null) {
            notificationBuilder
                .setContentText("0/$total")
                .setProgress(total, 0, false)
        } else {
            notificationBuilder
                .setContentText(getString(R.string.discovering_files))
                .setProgress(0, 0, true)
        }
        notificationManager.notify(lastNotificationId, notificationBuilder.build())
        return FileOperationNotification(notificationBuilder, lastNotificationId)
    }

    private fun updateNotificationProgress(notification: FileOperationNotification, progress: Int, total: Int){
        notification.notificationBuilder
                .setProgress(total, progress, false)
                .setContentText("$progress/$total")
        notificationManager.notify(notification.notificationId, notification.notificationBuilder.build())
    }

    private fun cancelNotification(notification: FileOperationNotification){
        notificationManager.cancel(notification.notificationId)
    }

    fun cancelOperation(notificationId: Int){
        tasks[notificationId]?.cancel()
    }

    open class TaskResult<T>(val cancelled: Boolean, val failedItem: T?)

    private suspend fun <T> waitForTask(notification: FileOperationNotification, task: Deferred<T>): TaskResult<T> {
        tasks[notification.notificationId] = task
        return try {
            TaskResult(false, task.await())
        } catch (e: CancellationException) {
            TaskResult(true, null)
        } finally {
            cancelNotification(notification)
        }
    }

    private fun copyFile(srcPath: String, dstPath: String, remoteGocryptfsVolume: GocryptfsVolume = gocryptfsVolume): Boolean {
        var success = true
        val srcHandleId = remoteGocryptfsVolume.openReadMode(srcPath)
        if (srcHandleId != -1){
            val dstHandleId = gocryptfsVolume.openWriteMode(dstPath)
            if (dstHandleId != -1){
                var offset: Long = 0
                val ioBuffer = ByteArray(GocryptfsVolume.DefaultBS)
                var length: Int
                while (remoteGocryptfsVolume.readFile(srcHandleId, offset, ioBuffer).also { length = it } > 0) {
                    val written = gocryptfsVolume.writeFile(dstHandleId, offset, ioBuffer, length).toLong()
                    if (written == length.toLong()) {
                        offset += written
                    } else {
                        success = false
                        break
                    }
                }
                gocryptfsVolume.closeFile(dstHandleId)
            } else {
                success = false
            }
            remoteGocryptfsVolume.closeFile(srcHandleId)
        } else {
            success = false
        }
        return success
    }

    suspend fun copyElements(
        items: ArrayList<OperationFile>,
        remoteGocryptfsVolume: GocryptfsVolume = gocryptfsVolume
    ): String? = coroutineScope {
        val notification = showNotification(R.string.file_op_copy_msg, items.size)
        val task = async {
            var failedItem: String? = null
            for (i in 0 until items.size) {
                withContext(Dispatchers.IO) {
                    if (items[i].explorerElement.isDirectory) {
                        if (!gocryptfsVolume.pathExists(items[i].dstPath!!)) {
                            if (!gocryptfsVolume.mkdir(items[i].dstPath!!)) {
                                failedItem = items[i].explorerElement.fullPath
                            }
                        }
                    } else {
                        if (!copyFile(items[i].explorerElement.fullPath, items[i].dstPath!!, remoteGocryptfsVolume)) {
                            failedItem = items[i].explorerElement.fullPath
                        }
                    }
                }
                if (failedItem == null) {
                    updateNotificationProgress(notification, i+1, items.size)
                } else {
                    break
                }
            }
            failedItem
        }
        // treat cancellation as success
        waitForTask(notification, task).failedItem
    }

    suspend fun moveElements(items: ArrayList<OperationFile>): String? = coroutineScope {
        val notification = showNotification(R.string.file_op_move_msg, items.size)
        val task = async {
            var failedItem: String? = null
            withContext(Dispatchers.IO) {
                val mergedFolders = ArrayList<String>()
                for (i in 0 until items.size) {
                    if (items[i].explorerElement.isDirectory && gocryptfsVolume.pathExists(items[i].dstPath!!)) { //folder will be merged
                        mergedFolders.add(items[i].explorerElement.fullPath)
                    } else {
                        if (!gocryptfsVolume.rename(items[i].explorerElement.fullPath, items[i].dstPath!!)) {
                            failedItem = items[i].explorerElement.fullPath
                            break
                        } else {
                            updateNotificationProgress(notification, i+1, items.size)
                        }
                    }
                }
                if (failedItem == null) {
                    for (i in 0 until mergedFolders.size) {
                        if (!gocryptfsVolume.rmdir(mergedFolders[i])) {
                            failedItem = mergedFolders[i]
                            break
                        } else {
                            updateNotificationProgress(notification, items.size-(mergedFolders.size-i), items.size)
                        }
                    }
                }
            }
            failedItem
        }
        // treat cancellation as success
        waitForTask(notification, task).failedItem
    }

    private suspend fun importFilesFromUris(
        dstPaths: List<String>,
        uris: List<Uri>,
        notification: FileOperationNotification,
    ): String? {
        var failedIndex = -1
        for (i in dstPaths.indices) {
            withContext(Dispatchers.IO) {
                try {
                    if (!gocryptfsVolume.importFile(this@FileOperationService, uris[i], dstPaths[i])) {
                        failedIndex = i
                    }
                } catch (e: FileNotFoundException) {
                    failedIndex = i
                }
            }
            if (failedIndex == -1) {
                updateNotificationProgress(notification, i+1, dstPaths.size)
            } else {
                return uris[failedIndex].toString()
            }
        }
        return null
    }

    suspend fun importFilesFromUris(dstPaths: List<String>, uris: List<Uri>): TaskResult<String?> = coroutineScope {
        val notification = showNotification(R.string.file_op_import_msg, dstPaths.size)
        val task = async {
            importFilesFromUris(dstPaths, uris, notification)
        }
        waitForTask(notification, task)
    }

    /**
     * Map the content of an unencrypted directory to prepare its import
     *
     * Contents of dstFiles and srcUris, at the same index, will match each other
     *
     * @return false if cancelled early, true otherwise.
     */
    private fun recursiveMapDirectoryForImport(
        rootSrcDir: DocumentFile,
        rootDstPath: String,
        dstFiles: ArrayList<String>,
        srcUris: ArrayList<Uri>,
        dstDirs: ArrayList<String>,
        scope: CoroutineScope,
    ): Boolean {
        dstDirs.add(rootDstPath)
        for (child in rootSrcDir.listFiles()) {
            if (!scope.isActive) {
                return false
            }
            child.name?.let { name ->
                val subPath = PathUtils.pathJoin(rootDstPath, name)
                if (child.isDirectory) {
                    if (!recursiveMapDirectoryForImport(child, subPath, dstFiles, srcUris, dstDirs, scope)) {
                        return false
                    }
                }
                else if (child.isFile) {
                    srcUris.add(child.uri)
                    dstFiles.add(subPath)
                }
            }
        }
        return true
    }

    class ImportDirectoryResult(val taskResult: TaskResult<String?>, val uris: List<Uri>)

    suspend fun importDirectory(
        rootDstPath: String,
        rootSrcDir: DocumentFile,
    ): ImportDirectoryResult = coroutineScope {
        val notification = showNotification(R.string.file_op_import_msg, null)
        val srcUris = arrayListOf<Uri>()
        val task = async {
            var failedItem: String? = null
            val dstFiles = arrayListOf<String>()
            val dstDirs = arrayListOf<String>()

            withContext(Dispatchers.IO) {
                if (!recursiveMapDirectoryForImport(rootSrcDir, rootDstPath, dstFiles, srcUris, dstDirs, this)) {
                    return@withContext
                }

                // create destination folders so the new files can use them
                for (dir in dstDirs) {
                    if (!gocryptfsVolume.mkdir(dir)) {
                        failedItem = dir
                        break
                    }
                }
            }
            if (failedItem == null) {
                failedItem = importFilesFromUris(dstFiles, srcUris, notification)
            }
            failedItem
        }
        ImportDirectoryResult(waitForTask(notification, task), srcUris)
    }

    suspend fun wipeUris(uris: List<Uri>, rootFile: DocumentFile? = null): String? = coroutineScope {
        val notification = showNotification(R.string.file_op_wiping_msg, uris.size)
        val task = async {
            var errorMsg: String? = null
            for (i in uris.indices) {
                withContext(Dispatchers.IO) {
                    errorMsg = Wiper.wipe(this@FileOperationService, uris[i])
                }
                if (errorMsg == null) {
                    updateNotificationProgress(notification, i+1, uris.size)
                } else {
                    break
                }
            }
            if (errorMsg == null) {
                rootFile?.delete()
            }
            errorMsg
        }
        // treat cancellation as success
        waitForTask(notification, task).failedItem
    }

    private fun exportFileInto(srcPath: String, treeDocumentFile: DocumentFile): Boolean {
        val outputStream = treeDocumentFile.createFile("*/*", File(srcPath).name)?.uri?.let {
            contentResolver.openOutputStream(it)
        }
        return if (outputStream == null) {
            false
        } else {
            gocryptfsVolume.exportFile(srcPath, outputStream)
        }
    }

    private fun recursiveExportDirectory(
        plain_directory_path: String,
        treeDocumentFile: DocumentFile,
        scope: CoroutineScope
    ): String? {
        treeDocumentFile.createDirectory(File(plain_directory_path).name)?.let { childTree ->
            val explorerElements = gocryptfsVolume.listDir(plain_directory_path)
            for (e in explorerElements) {
                if (!scope.isActive) {
                    return null
                }
                val fullPath = PathUtils.pathJoin(plain_directory_path, e.name)
                if (e.isDirectory) {
                    val failedItem = recursiveExportDirectory(fullPath, childTree, scope)
                    failedItem?.let { return it }
                } else {
                    if (!exportFileInto(fullPath, childTree)){
                        return fullPath
                    }
                }
            }
            return null
        }
        return treeDocumentFile.name
    }

    suspend fun exportFiles(uri: Uri, items: List<ExplorerElement>): TaskResult<String?> = coroutineScope {
        val notification = showNotification(R.string.file_op_export_msg, items.size)
        val task = async {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val treeDocumentFile = DocumentFile.fromTreeUri(this@FileOperationService, uri)!!
            var failedItem: String? = null
            for (i in items.indices) {
                withContext(Dispatchers.IO) {
                    failedItem = if (items[i].isDirectory) {
                        recursiveExportDirectory(items[i].fullPath, treeDocumentFile, this)
                    } else {
                        if (exportFileInto(items[i].fullPath, treeDocumentFile)) null else items[i].fullPath
                    }
                }
                if (failedItem == null) {
                    updateNotificationProgress(notification, i+1, items.size)
                } else {
                    break
                }
            }
            failedItem
        }
        waitForTask(notification, task)
    }

    private fun recursiveCountChildElements(rootDirectory: DocumentFile, scope: CoroutineScope): Int {
        if (!scope.isActive) {
            return 0
        }
        val children = rootDirectory.listFiles()
        var count = children.size
        for (child in children) {
            if (child.isDirectory) {
                count += recursiveCountChildElements(child, scope)
            }
        }
        return count
    }

    internal class ObjRef<T>(var value: T)

    private fun recursiveCopyVolume(
        src: DocumentFile,
        dst: DocumentFile,
        dstRootDirectory: ObjRef<DocumentFile?>?,
        notification: FileOperationNotification,
        total: Int,
        scope: CoroutineScope,
        progress: ObjRef<Int> = ObjRef(0)
    ): DocumentFile? {
        val dstDir = dst.createDirectory(src.name ?: return src) ?: return src
        dstRootDirectory?.let { it.value = dstDir }
        for (child in src.listFiles()) {
            if (!scope.isActive) {
                return null
            }
            if (child.isFile) {
                val dstFile = dstDir.createFile("", child.name ?: return child) ?: return child
                val outputStream = contentResolver.openOutputStream(dstFile.uri)
                val inputStream = contentResolver.openInputStream(child.uri)
                if (outputStream == null || inputStream == null) return child
                val written = inputStream.copyTo(outputStream)
                outputStream.close()
                inputStream.close()
                if (written != child.length()) return child
            } else {
                recursiveCopyVolume(child, dstDir, null, notification, total, scope, progress)?.let { return it }
            }
            progress.value++
            updateNotificationProgress(notification, progress.value, total)
        }
        return null
    }

    class CopyVolumeResult(val taskResult: TaskResult<DocumentFile?>, val dstRootDirectory: DocumentFile?)

    suspend fun copyVolume(src: DocumentFile, dst: DocumentFile): CopyVolumeResult = coroutineScope {
        val notification = showNotification(R.string.copy_volume_notification, null)
        val dstRootDirectory = ObjRef<DocumentFile?>(null)
        val task = async(Dispatchers.IO) {
            val total = recursiveCountChildElements(src, this)
            if (isActive) {
                updateNotificationProgress(notification, 0, total)
                recursiveCopyVolume(src, dst, dstRootDirectory, notification, total, this)
            } else {
                null
            }
        }
        // treat cancellation as success
        CopyVolumeResult(waitForTask(notification, task), dstRootDirectory.value)
    }
}