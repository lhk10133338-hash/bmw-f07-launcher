package com.hyteck.bmwf07launcher.util

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.util.Locale

/**
 * The launcher's own folder browser for the boot video / picture (v4.3).
 *
 * Why this exists: on the car unit the button used to hand the job to whatever picker the firmware
 * ships. That picker opens at `LOCAL > Ts` and offers nothing else — there is no way to reach a
 * folder the user put the file in (user report, 2026-11-26, photo of the `LOCAL / Ts` screen). So a
 * short press now opens this list, which walks the real directory tree:
 *
 *  - roots: the built-in storage, every volume `StorageManager` reports (SD card, USB stick) and
 *    every mounted `/storage/<id>` directory, plus the launcher's own `intro` folder;
 *  - a folder tap goes into its sub-folders, `⬑ 상위 폴더` goes back up, and only media files
 *    ([BootMediaPicker]'s suffixes) are listed, so the list stays readable on a car screen;
 *  - the firmware's own picker is still there as a secondary button for a unit where this list
 *    cannot see a file.
 *
 * The listing runs on a worker thread, because a folder such as `DCIM` can hold thousands of
 * entries and the settings screen must not freeze under a finger.
 */
class BootFileBrowser(
    private val activity: Activity,
    private val onPick: (Uri) -> Unit,
    /** The unit's own picker; offered as a dialog button only. */
    private val onSystemPicker: (() -> Unit)? = null,
    /** The gallery/MediaStore index ([BootMediaPicker.scan]); offered as a dialog button only. */
    private val onSearch: (() -> Unit)? = null
) {

    private enum class Kind { OPEN, UP, PICK, NOTE }

    private class Row(
        val text: String,
        val kind: Kind,
        val dir: File? = null,
        val file: File? = null
    )

    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var listView: ListView? = null
    private var rows: List<Row> = emptyList()

    /** Only the newest listing may be shown; a slow folder left mid-way is ignored. */
    private var generation = 0

    fun show() {
        val list = ListView(activity).apply {
            setBackgroundColor(0xFF000000.toInt())
            divider = null
            setPadding(0, activity.dp(2), 0, activity.dp(2))
        }
        listView = list
        val builder = AlertDialog.Builder(activity)
            .setView(list)
            .setNegativeButton("닫기", null)
        onSystemPicker?.let { pick -> builder.setNeutralButton("시스템 선택기") { _, _ -> pick() } }
        onSearch?.let { search -> builder.setPositiveButton("갤러리 색인") { _, _ -> search() } }
        val created = builder.create()
        created.setCanceledOnTouchOutside(true)
        dialog = created
        created.show()
        load(null)
    }

    // ---------------------------------------------------------------- listing

    /**
     * Fills the dialog from [dir], or with the root list when it is null. Runs on a worker thread
     * and posts the result back, so entering a large folder never blocks the settings screen.
     */
    private fun load(dir: File?) {
        val token = ++generation
        setTitle(if (dir == null) "저장소 선택 …" else "${dir.absolutePath} …")
        showRows(listOf(Row("불러오는 중 …", Kind.NOTE)))
        Thread {
            val (title, rows) = try {
                if (dir == null) rootRows() else folderRows(dir)
            } catch (t: Throwable) {
                (dir?.absolutePath ?: "저장소 선택") to
                    listOf(Row("이 위치를 읽을 수 없습니다\n${t.javaClass.simpleName}", Kind.NOTE))
            }
            handler.post {
                if (token != generation) return@post
                if (activity.isFinishing || activity.isDestroyed) return@post
                setTitle(title)
                showRows(rows)
            }
        }.start()
    }

    private fun setTitle(text: String) {
        dialog?.setTitle(text)
    }

    private fun showRows(items: List<Row>) {
        rows = items
        listView?.adapter = ItemAdapter(items)
    }

    private fun onRow(row: Row) {
        when (row.kind) {
            Kind.OPEN, Kind.UP -> row.dir?.let { load(it) }
            Kind.PICK -> row.file?.let { file ->
                dialog?.dismiss()
                onPick(Uri.fromFile(file))
            }
            Kind.NOTE -> Unit
        }
    }

    /**
     * The top of the tree: built-in storage first, then every volume the platform reports, then
     * whatever is mounted under `/storage` or `/mnt/media_rw` (a Chinese head unit often exposes a
     * USB stick only there), and finally the launcher's own `intro` folder.
     */
    private fun rootRows(): Pair<String, List<Row>> {
        val items = ArrayList<Row>()
        val seen = HashSet<String>()
        fun add(label: String, dir: File?) {
            if (dir == null) return
            val path = runCatching { dir.absolutePath }.getOrNull() ?: return
            if (!seen.add(path)) return
            if (!runCatching { dir.isDirectory && dir.canRead() }.getOrDefault(false)) return
            items.add(Row("📁  $label\n    $path", Kind.OPEN, dir = dir))
        }

        add("내장 저장소", runCatching { Environment.getExternalStorageDirectory() }.getOrNull())
        val manager = activity.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        manager?.storageVolumes?.forEach { volume ->
            val dir = volumeDirectory(volume)
            val description = runCatching { volume.getDescription(activity) }.getOrNull()
            val removable = runCatching { volume.isRemovable }.getOrDefault(false)
            val label = (description ?: "저장소") + if (removable) " (이동식)" else ""
            add(label, dir)
        }
        mountChildren("/storage").forEach { add("볼륨 ${it.name}", it) }
        mountChildren("/mnt/media_rw").forEach { add("USB ${it.name}", it) }
        add("런처 부팅 로고 폴더", File(activity.filesDir, INTRO_DIR))
        if (items.isEmpty()) {
            items.add(Row("읽을 수 있는 저장소가 없습니다\n저장소 권한을 확인해 주세요", Kind.NOTE))
        }
        return "저장소 선택" to items
    }

    private fun mountChildren(path: String): List<File> =
        runCatching { File(path).listFiles()?.toList() }.getOrNull()
            ?.filter { runCatching { it.isDirectory && it.canRead() }.getOrDefault(false) }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            ?: emptyList()

    /**
     * A volume's directory. `StorageVolume.getDirectory()` is API 30; before that (and for a
     * firmware that hides it) `getPath`/`getPathFile` through reflection, and the `/storage` scan
     * above still finds it either way.
     */
    private fun volumeDirectory(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { volume.directory }.getOrNull()?.let { return it }
        }
        runCatching {
            StorageVolume::class.java.getMethod("getPathFile").invoke(volume) as? File
        }.getOrNull()?.let { return it }
        return runCatching {
            (StorageVolume::class.java.getMethod("getPath").invoke(volume) as? String)
                ?.let { File(it) }
        }.getOrNull()
    }

    /** One folder: `⬑ 상위 폴더`, its sub-folders, then the video/picture files in it. */
    private fun folderRows(dir: File): Pair<String, List<Row>> {
        val title = runCatching { dir.absolutePath }.getOrDefault("저장소")
        val items = ArrayList<Row>()
        dir.parentFile?.let { parent ->
            if (runCatching { parent.canRead() }.getOrDefault(false)) {
                items.add(Row("⬑  상위 폴더\n    ${parent.absolutePath}", Kind.UP, dir = parent))
            }
        }
        val children = runCatching { dir.listFiles() }.getOrNull()
            ?: return title to items + Row("이 폴더를 읽을 수 없습니다\n$title", Kind.NOTE)

        val folders = children
            .filter { runCatching { it.isDirectory && !it.name.startsWith(".") }.getOrDefault(false) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
        val files = children
            .filter { runCatching { it.isFile && !it.name.startsWith(".") }.getOrDefault(false) }
            .filter { BootMediaPicker.isVideoName(it.name) || BootMediaPicker.isImageName(it.name) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }

        folders.take(MAX_ROWS).forEach {
            items.add(Row("📁  ${it.name}\n    폴더", Kind.OPEN, dir = it))
        }
        files.take(MAX_ROWS).forEach { file ->
            val icon = if (BootMediaPicker.isVideoName(file.name)) "🎬" else "🖼"
            items.add(
                Row("$icon  ${file.name}\n    ${sizeLabel(file.length())}", Kind.PICK, file = file)
            )
        }
        if (folders.size + files.size > MAX_ROWS * 2) {
            items.add(Row("항목이 너무 많아 ${MAX_ROWS * 2}개까지만 표시합니다", Kind.NOTE))
        }
        if (folders.isEmpty() && files.isEmpty()) {
            items.add(Row("이 폴더에는 하위 폴더나 동영상·사진이 없습니다", Kind.NOTE))
        }
        return title to items
    }

    private fun sizeLabel(bytes: Long): String = when {
        bytes <= 0L -> "?"
        bytes >= 1024L * 1024L * 1024L ->
            String.format(Locale.US, "%.1fGB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%dKB", bytes / 1024L)
    }

    // ---------------------------------------------------------------- adapter

    /** Two-line rows on the black dialog; a note row is dimmed and does nothing when tapped. */
    private inner class ItemAdapter(private val items: List<Row>) :
        ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1) {

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): String = items[position].text

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView as? TextView) ?: TextView(activity).apply {
                textSize = 15f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setPadding(activity.dp(16), activity.dp(10), activity.dp(16), activity.dp(10))
            }
            val row = items[position]
            view.text = row.text
            view.setTextColor(
                if (row.kind == Kind.NOTE) 0xFF9E9E9E.toInt() else 0xFFEDEDED.toInt()
            )
            return view
        }
    }

    companion object {
        private const val MAX_ROWS = 400
        private const val INTRO_DIR = "intro"
    }
}
