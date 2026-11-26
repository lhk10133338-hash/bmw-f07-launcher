package com.hyteck.bmwf07launcher.engine

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process

/**
 * Which of the two privileged routes this installation can use (v4.0).
 *
 * CarCar Launcher lives in a product image: its manifest carries
 * `android:sharedUserId="android.uid.system"` and it is signed with the platform key, so it may
 * create a *trusted* virtual display and inject touches into it directly. This launcher ships in
 * two build variants for the same reason:
 *
 *  - **standard** (no `sharedUserId`): the process is an ordinary app. The platform refuses
 *    `ADD_TRUSTED_DISPLAY` and `INJECT_EVENTS` to it, so the same work is done by
 *    [com.hyteck.bmwf07launcher.service.ShizukuControlService], which runs with the adb shell
 *    identity through Shizuku. Without Shizuku the engine falls back to the accessibility
 *    gesture route and, without that, to the free-form window engine.
 *  - **systemuid** (`sharedUserId="android.uid.system"`): installed with the platform signature
 *    on the car unit, the launcher process itself is the system UID, so both permissions are
 *    held and [SystemDisplayHost] / [com.hyteck.bmwf07launcher.touch.SystemTouchInjector] are
 *    used directly — exactly the path CarCar takes.
 *
 * Every check is a plain permission query: a variant that does not hold the permission simply
 * reports false and the engine picks the next route.
 */
object Privileges {

    const val PERMISSION_ADD_TRUSTED_DISPLAY = "android.permission.ADD_TRUSTED_DISPLAY"
    const val PERMISSION_INJECT_EVENTS = "android.permission.INJECT_EVENTS"

    /** True when this process *is* the system user (the SystemUID build). */
    fun isSystemUid(): Boolean = Process.myUid() == Process.SYSTEM_UID

    /** True when [permission] is granted to this package (or the process is the system UID). */
    fun holds(context: Context, permission: String): Boolean {
        if (isSystemUid()) return true
        return try {
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
    }

    /** A trusted virtual display may only be created with `ADD_TRUSTED_DISPLAY`. */
    fun canCreateTrustedDisplay(context: Context): Boolean =
        holds(context, PERMISSION_ADD_TRUSTED_DISPLAY)

    /**
     * Touches may only be injected into another display with `INJECT_EVENTS`.
     *
     * CarCar needs the same permission (its AppView injects a `MotionEvent` whose display id is
     * its virtual display), so the check matches what the original engine relies on.
     */
    fun canInjectInput(context: Context): Boolean =
        holds(context, PERMISSION_INJECT_EVENTS)

    /** True when this build is the platform-signed `android.uid.system` variant. */
    fun isSystemBuild(context: Context): Boolean =
        isSystemUid() || canCreateTrustedDisplay(context)
}
