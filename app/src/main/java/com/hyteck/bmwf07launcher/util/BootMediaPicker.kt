package com.hyteck.bmwf07launcher.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.Locale

/** One media file the launcher's own browser can offer as the boot logo. */
data class BootMedia(
    val name: String,
    val path: String?,
    val uri: Uri,
    val isVideo: Boolean,
    val sizeBytes: Long
) {
    /** "동영상 · 12.3MB · name.mp4" — the line the picker dialog shows. */
    fun label(): String {
        val kind = if (isVideo) "동영상" else "사진"
        return "$kind · ${sizeLabel()} · $name"
    }

    fun sizeLabel(): String = when {
        sizeBytes <= 0L -> "?"
        sizeBytes >= 1024L * 1024L * 1024L ->
            String.format(Locale.US, "%.1fGB", sizeBytes / (1024.0 * 1024.0 * 1024.0))
        sizeBytes >= 1024L * 1024L ->
            String.format(Locale.US, "%.1fMB", sizeBytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%dKB", sizeBytes / 1024L)
    }
}

/**
 * Finds a boot video or picture on a unit that has **no working document picker** (v4.2).
 *
 * Many Chinese head units ship without `DocumentsUI`, and the SystemUID build runs as
 * `android.uid.system`, which some firmware refuses to hand `ACTION_OPEN_DOCUMENT` to. The picker
 * button therefore walks a chain of intents (`OPEN_DOCUMENT` → `GET_CONTENT` → `PICK`) and, when
 * nothing on the unit answers at all, the launcher lists the media itself:
 *
 *  - `MediaStore` videos and pictures (the gallery's own index), and
 *  - a direct scan of Download / Movies / Pictures / DCIM / Music and the app's own folders.
 *
 * The chosen file is copied into the app's private storage, because the boot logo is played before
 * any URI grant exists and must not depend on a provider being alive at boot.
 */
object BootMediaPicker {

    private val VIDEO_SUFFIXES =
        listOf("mp4", "mkv", "webm", "3gp", "avi", "mov", "m4v", "mpg", "mpeg", "ts")
    private val IMAGE_SUFFIXES =
        listOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")

    /** Guards a runaway recursive scan of a large DCIM folder. */
    private const val MAX_DEPTH = 3

    /** How many files the built-in list shows; the unit has no reason to hold more than that. */
    private const val MAX_ENTRIES = 200

    fun isImageName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return IMAGE_SUFFIXES.any { lower.endsWith(".$it") }
    }

    fun isVideoName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return VIDEO_SUFFIXES.any { lower.endsWith(".$it") }
    }

    /** The MIME family stored next to the intro URI (the launcher branches on it). */
    fun mediaTypeOf(name: String): String = if (isImageName(name)) "image/*" else "video/*"

    /**
     * The pickers in the order they are tried: the document picker first (it can hand out a
     * persistable URI), then the old gallery/file intents that units without `DocumentsUI` still
     * answer.
     */
    private fun pickerIntents(): List<Intent> {
        val mimeTypes = arrayOf("video/*", "image/*")
        return listOf(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            },
            Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            Intent(Intent.ACTION_PICK).apply {
                setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            Intent(Intent.ACTION_PICK).apply {
                setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "video/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }

    /**
     * Tries every picker until one of them starts. Returns false when this unit has none, which is
     * the caller's signal to show the built-in browser.
     *
     * The launch itself is the test: a `resolveActivity` lookup would need package-visibility
     * grants, and some firmware accepts the intent here and fails inside the picker anyway.
     */
    fun launch(launch: (Intent) -> Unit): Boolean {
        for (intent in pickerIntents()) {
            try {
                launch(intent)
                return true
            } catch (t: Throwable) {
                // ActivityNotFoundException (nothing answers) or a refusal for the system UID.
            }
        }
        return false
    }

    /**
     * Every video and picture the launcher can reach, videos first. Safe to call off the main
     * thread; every source is wrapped, so a firmware that refuses one still yields the others.
     */
    fun scan(context: Context): List<BootMedia> {
        val found = LinkedHashMap<String, BootMedia>()
        queryStore(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, found)
        queryStore(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, found)
        for (root in scanRoots(context)) {
            walk(root, 0, found)
        }
        return found.values
            .sortedWith(compareByDescending<BootMedia> { it.isVideo }.thenBy { it.name.lowercase(Locale.ROOT) })
            .take(MAX_ENTRIES)
    }

    private fun queryStore(
        context: Context,
        collection: Uri,
        video: Boolean,
        out: MutableMap<String, BootMedia>
    ) {
        val withPath = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATA
        )
        var rows = runCatching {
            context.contentResolver.query(collection, withPath, null, null, null)
        }.getOrNull()
        if (rows == null) {
            // Android 13+ hides _data for some providers: the same index without the path.
            val withoutPath = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE
            )
            rows = runCatching {
                context.contentResolver.query(collection, withoutPath, null, null, null)
            }.getOrNull()
        }
        rows?.use { cursor ->
            val idColumn = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val pathColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            while (cursor.moveToNext() && out.size < MAX_ENTRIES) {
                val id = if (idColumn >= 0) cursor.getLong(idColumn) else continue
                val name = if (nameColumn >= 0) cursor.getString(nameColumn) else null
                val size = if (sizeColumn >= 0) cursor.getLong(sizeColumn) else 0L
                val path = if (pathColumn >= 0) cursor.getString(pathColumn) else null
                val displayName = name ?: path?.substringAfterLast('/') ?: "media-$id"
                if (displayName.isBlank()) continue
                if (video && !isVideoName(displayName)) continue
                if (!video && !isImageName(displayName)) continue
                val uri = Uri.withAppendedPath(collection, id.toString())
                // Keyed by path when known, so the same file found by the folder scan below is
                // not listed twice.
                val key = if (path != null) "f:$path" else "c:$uri"
                if (out.containsKey(key)) continue
                out[key] = BootMedia(displayName, path, uri, video, size)
            }
        }
    }

    private fun scanRoots(context: Context): List<File> {
        val roots = ArrayList<File>()
        for (name in listOf("Download", "Movies", "Pictures", "DCIM", "Music")) {
            runCatching { Environment.getExternalStoragePublicDirectory(name) }
                .getOrNull()
                ?.let { roots.add(it) }
        }
        context.getExternalFilesDir(null)?.let { roots.add(it) }
        roots.add(File(context.filesDir, INTRO_DIR))
        return roots.filter { it.isDirectory }
    }

    private fun walk(dir: File, depth: Int, out: MutableMap<String, BootMedia>) {
        if (depth > MAX_DEPTH || out.size >= MAX_ENTRIES) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (file in children) {
            if (out.size >= MAX_ENTRIES) return
            if (file.isDirectory) {
                walk(file, depth + 1, out)
                continue
            }
            val name = file.name
            val video = isVideoName(name)
            if (!video && !isImageName(name)) continue
            val key = "f:" + file.absolutePath
            if (out.containsKey(key)) continue
            out[key] = BootMedia(name, file.absolutePath, Uri.fromFile(file), video, file.length())
        }
    }

    /**
     * Copies [uri] into the app's private `intro` folder and returns the copy, or null when the
     * file cannot be read. Copying (instead of keeping a `content://` URI) means the boot logo
     * still plays after a reboot, when no provider grant exists any more.
     */
    fun copyIn(context: Context, uri: Uri, fallbackName: String): File? {
        val dir = File(context.filesDir, INTRO_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val name = fileNameOf(context, uri, fallbackName)
        // v4.11: the new file is read into a temporary name first. The old copy used to be deleted
        // before the new one was read, so re-picking the intro that is already in this folder — it is
        // one of the browser's roots — deleted the only good file and then failed to copy it back.
        val temp = File(dir, TEMP_NAME)
        runCatching { temp.delete() }
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            temp.length() > 0L
        }.getOrDefault(false)
        if (!copied) {
            // A scanned hit is a plain file: read it directly if the resolver refused the handle.
            val source = uri.path
            if (source != null) {
                runCatching { File(source).copyTo(temp, overwrite = true) }
            }
        }
        if (!temp.isFile || temp.length() <= 0L) {
            runCatching { temp.delete() }
            return null
        }
        // One boot logo at a time: the previous copy is dropped only now that the new one is here.
        runCatching { dir.listFiles()?.forEach { if (it.isFile && it != temp) it.delete() } }
        val target = File(dir, name)
        runCatching { temp.copyTo(target, overwrite = true) }
        runCatching { temp.delete() }
        return if (target.length() > 0L) target else null
    }

    private fun fileNameOf(context: Context, uri: Uri, fallbackName: String): String {
        var name: String? = null
        if (uri.scheme == "content") {
            name = runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst() && cursor.columnCount > 0) cursor.getString(0) else null
                }
            }.getOrNull()
        }
        if (name.isNullOrBlank()) name = uri.lastPathSegment
        if (name.isNullOrBlank()) name = fallbackName
        var safe = name.substringAfterLast('/').substringAfterLast('\\').trim()
        if (safe.isBlank()) safe = fallbackName
        if (!isVideoName(safe) && !isImageName(safe)) {
            val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            val suffix = when {
                type?.startsWith("image") == true -> "jpg"
                type?.startsWith("video") == true -> "mp4"
                else -> "mp4"
            }
            safe = "$safe.$suffix"
        }
        return safe
    }

    private const val INTRO_DIR = "intro"

    /** The name a new copy is read into before it replaces the previous one (v4.11). */
    private const val TEMP_NAME = "boot-logo-copy.tmp"
}
