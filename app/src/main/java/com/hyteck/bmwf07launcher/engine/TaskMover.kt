package com.hyteck.bmwf07launcher.engine

import android.util.Log
import java.lang.reflect.Method

/**
 * Moves the apps that are running on one virtual display onto another one **without restarting
 * them** (v4.5).
 *
 * ## Why this exists
 *
 * v4.5 replaces a pane's display instead of resizing it: on this firmware a display that has been
 * created against one surface keeps that binding, and pointing it at another surface or resizing it
 * leaves the pane black for good, while a freshly created display always draws (the user's own
 * observation: "앱을 껐다 켜면 정상으로 돌아온다"). The original CarCar launcher never hits that,
 * because it swaps in a new Flutter SurfaceProducer for every size change (`La8/c0.b()`).
 *
 * Replacing the display would normally take the app with it — `DESTROY_CONTENT_ON_REMOVAL` throws
 * away the content of a released display — which for a navigation app means losing the route and
 * for a music app at least its UI. So before the old display is released, the root task holding the
 * app is moved onto the new display with the platform's own call:
 *
 *   `ActivityTaskManager.getService().moveRootTaskToDisplay(rootTaskId, displayId)`
 *
 * which is exactly what `am display move-stack <rootTaskId> <displayId>` runs
 * (`ActivityTaskManagerService.moveRootTaskToDisplay` → `RootWindowContainer.moveRootTaskToDisplay`
 * → `Task.reparent`, permission `INTERNAL_SYSTEM_WINDOW`). The SystemUID build declares that
 * permission and, being `android.uid.system` + platform-signed, is granted it; the call is made
 * through reflection because `ActivityTaskManager` / `IActivityTaskManager` are `@hide`.
 *
 * A build that may not call it (the standard build without Shizuku, or a firmware that refuses the
 * call) simply returns false and the caller launches the app on the new display instead — the
 * picture is what matters, the app's state is kept whenever the platform allows it.
 *
 * Every entry point is defensive: unknown signatures, refused permissions and dead binders return
 * null/false and are logged. Nothing here can crash the launcher.
 */
internal object TaskMover {

    private const val TAG = "MNLauncher"

    /** `ActivityTaskManager.getService()` — the `IActivityTaskManager` binder proxy. */
    private val service: Any? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService")
                .invoke(null)
        } catch (t: Throwable) {
            Log.i(TAG, "TaskMover: ActivityTaskManager.getService() unavailable (${t.javaClass.simpleName})")
            null
        }
    }

    private var reportedFailure = false

    /**
     * Moves every root task that lives on [fromDisplay] onto [toDisplay] and returns true when the
     * platform confirms that a task of [packageName] is now on the new display.
     */
    fun moveRootTasks(fromDisplay: Int, toDisplay: Int, packageName: String?): Boolean {
        if (fromDisplay < 0 || toDisplay < 0 || fromDisplay == toDisplay) return false
        val service = service ?: return false
        val rootTaskIds = rootTaskIdsOn(service, fromDisplay)
        if (rootTaskIds.isEmpty()) {
            Log.i(TAG, "TaskMover: no root task listed on display $fromDisplay")
            return false
        }
        var called = false
        for (rootTaskId in rootTaskIds) {
            val ok = invoke(service, "moveRootTaskToDisplay", rootTaskId, toDisplay)
            Log.i(
                TAG,
                "TaskMover: moveRootTaskToDisplay(rootTask=$rootTaskId, display=$toDisplay) -> $ok"
            )
            if (ok) called = true
        }
        if (!called) {
            // Some firmwares expose the task-level form instead.
            for (taskId in taskIdsOn(service, fromDisplay)) {
                if (invoke(service, "moveTaskToDisplay", taskId, toDisplay)) {
                    called = true
                    Log.i(TAG, "TaskMover: moveTaskToDisplay(task=$taskId, display=$toDisplay) ok")
                }
            }
        }
        if (!called) {
            noteFailure("move refused")
            return false
        }
        val tasks = tasksOn(service, toDisplay)
        val arrived = if (packageName == null) tasks.isNotEmpty()
        else tasks.any { taskPackage(it) == packageName }
        if (!arrived) {
            noteFailure("task did not arrive on display $toDisplay")
        } else {
            Log.i(TAG, "TaskMover: ${tasks.size} task(s) now on display $toDisplay")
        }
        return arrived
    }

    /**
     * True when [packageName] still has a task on [displayId].
     *
     * Deliberately conservative: when the platform cannot list tasks, or lists tasks whose package
     * cannot be read, the answer is "yes" — a caller must never restart an app out of ignorance.
     */
    fun hasTask(displayId: Int, packageName: String): Boolean {
        if (displayId < 0) return false
        val service = service ?: return true
        val tasks = tasksOn(service, displayId)
        if (tasks.isEmpty()) return true
        val names = tasks.mapNotNull { taskPackage(it) }
        if (names.isEmpty()) return true
        return names.contains(packageName)
    }

    // ------------------------------------------------------------------ internals

    private fun tasksOn(service: Any, displayId: Int): List<Any> {
        // IActivityTaskManager.getTasks(int maxNum, boolean filterOnlyVisibleRecents,
        //                              boolean keepIntentExtra, int displayId)
        val result = invokeForResult(service, "getTasks", MAX_TASKS, 0, 0, displayId)
            ?: invokeForResult(service, "getTasks", MAX_TASKS, 0, displayId)
            ?: invokeForResult(service, "getTasks", MAX_TASKS, 0)
        return asList(result)
    }

    private fun taskIdsOn(service: Any, displayId: Int): List<Int> =
        tasksOn(service, displayId).mapNotNull { intField(it, "taskId") }

    /**
     * Root task ids on [displayId]. `getAllRootTaskInfosOnDisplay` is the direct form; older
     * firmwares only have `getRootTaskInfoOnDisplay(windowingMode, activityType, displayId)`.
     *
     * Every id is one the platform reports for *this* display: `moveRootTaskToDisplay` takes a
     * root task id (`am display move-stack <rootTaskId> <displayId>`), and handing it a plain task
     * id only makes it throw, which [moveRootTasks] then reports as "refused".
     */
    private fun rootTaskIdsOn(service: Any, displayId: Int): List<Int> {
        asList(invokeForResult(service, "getAllRootTaskInfosOnDisplay", displayId))
            .filter { ownDisplay(it, displayId) }
            .let { roots ->
                val ids = roots.mapNotNull { intField(it, "taskId") }
                if (ids.isNotEmpty()) return ids
            }
        for (windowingMode in intArrayOf(WINDOWING_MODE_UNDEFINED, WINDOWING_MODE_FULLSCREEN)) {
            val info = invokeForResult(
                service,
                "getRootTaskInfoOnDisplay",
                windowingMode,
                ACTIVITY_TYPE_UNDEFINED,
                displayId
            )
            if (ownDisplay(info, displayId)) intField(info, "taskId")?.let { return listOf(it) }
        }
        // Last resort: the app's own task ids on that display. `moveRootTaskToDisplay` rejects an
        // id that is not a root task with an IllegalArgumentException, which invoke() swallows.
        return taskIdsOn(service, displayId)
    }

    /** False only when the info names another display; an info without the field is accepted. */
    private fun ownDisplay(info: Any?, displayId: Int): Boolean {
        if (info == null) return false
        val reported = intField(info, "displayId") ?: return true
        return reported == displayId
    }

    private fun taskPackage(task: Any): String? {
        for (field in arrayOf("topActivity", "baseActivity")) {
            try {
                val value = task.javaClass.getField(field).get(task)
                if (value is android.content.ComponentName) return value.packageName
            } catch (t: Throwable) {
                // Field missing on this firmware.
            }
        }
        return try {
            val intent = task.javaClass.getField("baseIntent").get(task) as? android.content.Intent
            intent?.component?.packageName
        } catch (t: Throwable) {
            null
        }
    }

    private fun intField(target: Any?, name: String): Int? {
        if (target == null) return null
        return try {
            target.javaClass.getField(name).getInt(target)
        } catch (t: Throwable) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun asList(result: Any?): List<Any> = (result as? List<Any>) ?: emptyList()

    private fun invokeForResult(service: Any, name: String, vararg ints: Int): Any? {
        val method = find(service, name, ints.size) ?: return null
        return try {
            method.isAccessible = true
            method.invoke(service, *argsFor(method, ints))
        } catch (t: Throwable) {
            noteFailure("$name failed: ${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    private fun invoke(service: Any, name: String, vararg ints: Int): Boolean {
        val method = find(service, name, ints.size) ?: return false
        return try {
            method.isAccessible = true
            method.invoke(service, *argsFor(method, ints))
            true
        } catch (t: Throwable) {
            noteFailure("$name failed: ${t.javaClass.simpleName} ${t.message}")
            false
        }
    }

    /**
     * Builds the argument list for [method]: the integer/bool arguments we know are filled from
     * [ints], and anything else (a callback, an out parameter) is left null. The firmware's
     * signatures only ever put the numbers in the leading positions.
     */
    private fun argsFor(method: Method, ints: IntArray): Array<Any?> {
        val types = method.parameterTypes
        val args = arrayOfNulls<Any>(types.size)
        var next = 0
        for (index in types.indices) {
            when (types[index]) {
                Int::class.javaPrimitiveType, Int::class.javaObjectType ->
                    if (next < ints.size) args[index] = ints[next++] else args[index] = 0
                java.lang.Boolean.TYPE, java.lang.Boolean::class.javaObjectType ->
                    if (next < ints.size) args[index] = ints[next++] == 1 else args[index] = false
                else -> args[index] = null
            }
        }
        return args
    }

    /**
     * A method with exactly [count] numeric parameters whose name matches (firmware helpers put the
     * display and task ids in the leading positions).
     */
    private fun find(service: Any, name: String, count: Int): Method? {
        val candidates = try {
            service.javaClass.methods.filter { it.name == name }
        } catch (t: Throwable) {
            return null
        }
        return candidates.firstOrNull { method ->
            method.parameterTypes.size == count && leadingTypesAreNumeric(method, count)
        } ?: candidates.firstOrNull { it.parameterTypes.size == count }
    }

    private fun leadingTypesAreNumeric(method: Method, count: Int): Boolean {
        val types = method.parameterTypes
        for (index in 0 until minOf(count, types.size)) {
            val type = types[index]
            val numeric = type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType ||
                type == java.lang.Boolean.TYPE || type == java.lang.Boolean::class.javaObjectType
            if (!numeric) return false
        }
        return true
    }

    private fun noteFailure(reason: String) {
        if (reportedFailure) {
            Log.d(TAG, "TaskMover: $reason")
            return
        }
        reportedFailure = true
        Log.w(TAG, "TaskMover: $reason — the app will be started again on the new display instead")
    }

    private const val MAX_TASKS = 32

    /** ActivityTaskManager.WINDOWING_MODE_* / ACTIVITY_TYPE_* (hidden constants). */
    private const val WINDOWING_MODE_UNDEFINED = -1
    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val ACTIVITY_TYPE_UNDEFINED = 0
}
