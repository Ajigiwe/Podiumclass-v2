package online.podiumclass.app.data.recording

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One class recording that lives on this phone.
 *
 * Host recordings are captured here now, not on the server: a class no longer
 * writes an MP4 to the deployment's disk, and the host keeps the file even if
 * that deployment is replaced. `uri` is the MediaStore row on Android 10+, or a
 * plain file path on 8/9, where public media folders still need a storage
 * permission the app does not ask for.
 */
@Serializable
data class LocalRecording(
    val id: String,
    val uri: String,
    val title: String,
    val fileName: String,
    val startedAtMillis: Long,
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    /** Written while the recorder is still open; the MP4 is not playable yet. */
    val pending: Boolean = false
)

/**
 * The list of recordings this app made on this device.
 *
 * It is an index file rather than a MediaStore query on purpose: querying every
 * video in `Movies/Podium` would return files other apps put there and, on
 * Android 11-12, needs `READ_EXTERNAL_STORAGE` for anything the app did not
 * write itself. Keeping our own list means no storage permission at all -- and
 * an entry whose file has since been deleted is dropped on read, so the shelf
 * cannot show a video that is no longer there.
 */
object LocalRecords {

    private const val INDEX_FILE = "local_recordings.json"

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private fun indexFile(context: Context) = File(context.filesDir, INDEX_FILE)

    /** Every recording still present, newest first. */
    fun all(context: Context): List<LocalRecording> {
        val entries = synchronized(lock) { read(context) }
        val (live, gone) = entries.partition { exists(context, it) }
        if (gone.isNotEmpty()) {
            synchronized(lock) { write(context, live) }
        }
        return live.sortedByDescending { it.startedAtMillis }
    }

    fun add(context: Context, recording: LocalRecording) {
        synchronized(lock) {
            write(context, read(context).filterNot { it.id == recording.id } + recording)
        }
    }

    /** Replace one entry, leaving the others alone. */
    fun update(context: Context, id: String, block: (LocalRecording) -> LocalRecording) {
        synchronized(lock) {
            write(context, read(context).map { if (it.id == id) block(it) else it })
        }
    }

    /**
     * Forget a recording, and delete the video itself.
     *
     * This is the host's own file on their own phone: removing the row from the
     * shelf has to remove the megabytes too, or a full disk becomes the real
     * limit on how many classes can be recorded.
     */
    fun remove(context: Context, recording: LocalRecording) {
        try {
            val uri = Uri.parse(recording.uri)
            if (uri.scheme == "file") {
                uri.path?.let { File(it).delete() }
            } else {
                context.contentResolver.delete(uri, null, null)
            }
        } catch (_: Exception) {
            // The file may already be gone (the user deleted it in Files). The
            // index entry still has to go, which is what happens next.
        }
        synchronized(lock) {
            write(context, read(context).filterNot { it.id == recording.id })
        }
    }

    private fun read(context: Context): List<LocalRecording> {
        val file = indexFile(context)
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<List<LocalRecording>>(file.readText())
        } catch (_: Exception) {
            // A corrupt index must not take the Records tab down with it.
            emptyList()
        }
    }

    private fun write(context: Context, entries: List<LocalRecording>) {
        try {
            indexFile(context).writeText(
                json.encodeToString(ListSerializer(LocalRecording.serializer()), entries)
            )
        } catch (_: Exception) {
            // Storage full or the directory is gone: the video is still on the
            // disk, so the worst case is a recording missing from this list.
        }
    }

    private fun exists(context: Context, recording: LocalRecording): Boolean {
        val uri = Uri.parse(recording.uri)
        if (uri.scheme == "file") {
            return uri.path?.let { File(it).exists() } == true
        }
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
                null,
                null,
                null
            )?.use { it.count > 0 } == true
        } catch (_: Exception) {
            false
        }
    }
}
