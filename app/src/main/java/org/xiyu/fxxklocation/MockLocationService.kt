package org.xiyu.fxxklocation

import android.content.Context
import android.location.Location
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel

/**
 * Binder stub implementing IMockLocationManager AIDL (36 transaction codes).
 * Runs in system_server. Uses LocationManager to inject mock locations
 * via addTestProvider + setTestProviderLocation with system UID privileges.
 */
internal class MockLocationBinder(
    private val ctx: Context,
    private val bypassRemoveProvider: ThreadLocal<Boolean>
) : android.os.Binder() {
    // Lazy LocationManager — avoids NPE when created early in system_server boot
    private val lm: android.location.LocationManager?
        get() = try {
            ctx.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        } catch (_: Throwable) { null }

    @Volatile var mocking = false
    @Volatile var currentLocation: Location? = null
    @Volatile var loopInterval: Long = 1000L
    @Volatile var autoMode = false
    @Volatile var enforcementFlag = false
    @Volatile var enforcementThread: Thread? = null

    private var localBlacklist = ArrayList<String>()
    private var cloudBlacklist = ArrayList<String>()

    // Step simulation state
    @Volatile var stepSimActive = false
    @Volatile var stepSpeed: Float = 1.5f    // meters/second
    @Volatile var stepBaseline: Long = 0L     // hardware step counter baseline
    @Volatile var stepStartTime: Long = 0L    // when step sim started

    private val PROVIDERS = arrayOf("gps", "network")

    init {
        attachInterface(null, FL_AIDL_DESCRIPTOR)
    }

    override fun getInterfaceDescriptor(): String = FL_AIDL_DESCRIPTOR

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        try {
            if (code in 1..36) data.enforceInterface(FL_AIDL_DESCRIPTOR)
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(FL_AIDL_DESCRIPTOR)
                return true
            }
            val r = reply ?: return false
            when (code) {
                1 -> { data.readString(); data.readString(); r.writeNoException() }                // setConfigKV
                2 -> { doStartMock(); startEnforcementLoop(); r.writeNoException() }               // startMock
                3 -> { doStopMock(); r.writeNoException() }                                        // stopMock
                4 -> { r.writeNoException(); r.writeInt(if (mocking) 1 else 0) }                   // isMocking2
                5 -> {                                                                              // setLocation
                    val loc = if (data.readInt() != 0) Location.CREATOR.createFromParcel(data) else null
                    if (loc != null) {
                        if (!mocking) { doStartMock(); startEnforcementLoop() }
                        doSetLocation(loc)
                    }
                    r.writeNoException()
                }
                6 -> {                                                                              // getLastLocation
                    r.writeNoException()
                    val loc = currentLocation
                    if (loc != null) { r.writeInt(1); loc.writeToParcel(r, 0) } else r.writeInt(0)
                }
                7 -> { loopInterval = data.readLong(); r.writeNoException() }                      // setDelay
                8 -> { r.writeNoException(); r.writeLong(loopInterval) }                            // getInterval
                9 -> { localBlacklist = data.createStringArrayList() ?: ArrayList(); r.writeNoException() } // setLocalBlacklist
                10 -> { r.writeNoException(); r.writeStringList(ArrayList(filterBlacklist(localBlacklist))) } // getLocalBlacklist
                11 -> {                                                                             // startStepSim
                    stepSimActive = true
                    stepStartTime = System.currentTimeMillis()
                    log("[SYS-ML] step sim started (speed=${stepSpeed}, baseline=${stepBaseline})")
                    r.writeNoException()
                }
                12 -> {                                                                             // stopStepSim
                    stepSimActive = false
                    log("[SYS-ML] step sim stopped")
                    r.writeNoException()
                }
                13 -> { r.writeNoException(); r.writeInt(0) }                                       // isFeatureEnabled
                14 -> {                                                                             // setSpeed (step cadence)
                    stepSpeed = data.readFloat()
                    log("[SYS-ML] step speed set: $stepSpeed")
                    r.writeNoException()
                }
                15 -> { r.writeNoException(); r.writeFloat(stepSpeed) }                             // getSpeed
                16 -> { data.readLong(); r.writeNoException() }                                     // setTiming2
                17 -> { r.writeNoException(); r.writeLong(0L) }                                     // getTimestamp
                18 -> { data.readStrongBinder(); r.writeNoException() }                             // addListener
                19 -> { data.readStrongBinder(); r.writeNoException() }                             // removeListener
                20 -> { r.writeNoException() }                                                      // setCellInfos
                21 -> { r.writeNoException(); r.writeInt(-1) }                                      // getCellInfos → null
                22 -> { r.writeNoException(); r.writeInt(if (mocking) 1 else 0) }                   // isMocking
                23 -> { autoMode = data.readInt() != 0; r.writeNoException() }                      // setAutoMode
                24 -> { enforcementFlag = data.readInt() != 0; r.writeNoException() }               // setFlag
                25 -> { r.writeNoException(); r.writeInt(if (enforcementFlag) 1 else 0) }           // getEnforcementFlag
                26 -> { r.writeNoException(); r.writeInt(0) }                                       // isFeature2
                27 -> { data.readInt(); r.writeNoException() }                                      // setEnabled2
                28 -> { r.writeNoException() }                                                      // setSubInfos
                29 -> { r.writeNoException(); r.writeInt(-1) }                                      // getSubInfos → null
                30 -> { data.readInt(); r.writeNoException() }                                      // setEnabled4
                31 -> { r.writeNoException(); r.writeInt(0) }                                       // isFeature1
                32 -> { cloudBlacklist = data.createStringArrayList() ?: ArrayList(); r.writeNoException() } // setCloudBlacklist
                33 -> { r.writeNoException(); r.writeStringList(ArrayList(filterBlacklist(cloudBlacklist))) } // getCloudBlacklist
                34 -> {                                                                             // setTiming (step baseline)
                    stepBaseline = data.readLong()
                    log("[SYS-ML] step baseline set: $stepBaseline")
                    r.writeNoException()
                }
                35 -> { r.writeNoException(); r.writeInt(if (autoMode) 1 else 0) }                  // isAutoMode
                36 -> { autoMode = data.readInt() != 0; r.writeNoException() }                      // setEnabled3
                else -> return super.onTransact(code, data, r, flags)
            }
            return true
        } catch (e: Throwable) {
            log("[SYS-ML] onTransact($code) SAFE-CATCH: $e")
            try { reply?.writeException(Exception(e)) } catch (_: Throwable) {}
            return true
        }
    }

    private fun doStartMock() {
        mocking = true  // Set BEFORE thread starts so enforcement loop doesn't exit immediately
        log("[SYS-ML] doStartMock called, uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()}")
        Thread {
            val token = android.os.Binder.clearCallingIdentity()
            try {
                val locMgr = lm
                if (locMgr == null) {
                    log("[SYS-ML] startMock FAILED: LocationManager is null (system not ready)")
                    mocking = false
                    return@Thread
                }
                for (p in PROVIDERS) {
                    try {
                        bypassRemoveProvider.set(true)
                        locMgr.removeTestProvider(p)
                        log("[SYS-ML] removeTestProvider($p) OK")
                    } catch (e: Throwable) {
                        log("[SYS-ML] removeTestProvider($p): ${e.message}")
                    } finally {
                        bypassRemoveProvider.set(false)
                    }
                    try {
                        locMgr.addTestProvider(p, false, false, false, false, true, true, true, 1, 1)
                        locMgr.setTestProviderEnabled(p, true)
                        log("[SYS-ML] addTestProvider($p) + enabled OK")
                    } catch (e: Throwable) {
                        log("[SYS-ML] addTestProvider($p) FAILED: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
                log("[SYS-ML] startMock: test providers ready")
            } catch (e: Throwable) {
                log("[SYS-ML] startMock FAILED: $e")
                mocking = false
            } finally {
                android.os.Binder.restoreCallingIdentity(token)
            }
        }.apply { name = "FL-StartMock"; isDaemon = true; start() }
    }

    private fun doStopMock() {
        mocking = false
        enforcementThread?.interrupt()
        enforcementThread = null
        currentLocation = null
        Thread {
            val token = android.os.Binder.clearCallingIdentity()
            try {
                val locMgr = lm ?: return@Thread
                for (p in PROVIDERS) {
                    try {
                        bypassRemoveProvider.set(true)
                        locMgr.removeTestProvider(p)
                    } catch (_: Throwable) {}
                    finally { bypassRemoveProvider.set(false) }
                }
            } catch (_: Throwable) {}
            finally {
                android.os.Binder.restoreCallingIdentity(token)
            }
            log("[SYS-ML] stopMock: cleaned up")
        }.apply { name = "FL-StopMock"; isDaemon = true; start() }
    }

    private fun doSetLocation(location: Location) {
        currentLocation = location
        pushLocation(location)
    }

    /** Push location to all test providers with fresh timestamps and realistic GPS metadata. */
    private fun pushLocation(location: Location) {
        val locMgr = lm ?: return
        val token = android.os.Binder.clearCallingIdentity()
        try {
            val now = System.currentTimeMillis()
            val elapsed = android.os.SystemClock.elapsedRealtimeNanos()
            for (p in PROVIDERS) {
                try {
                    val loc = Location(location)
                    loc.provider = p
                    loc.time = now
                    loc.elapsedRealtimeNanos = elapsed
                    if (loc.accuracy <= 0f) loc.accuracy = 3.0f
                    // Realistic GPS metadata — many apps check these
                    if (!loc.hasAltitude()) loc.altitude = 45.0 + (Math.random() * 5)
                    if (!loc.hasBearing()) loc.bearing = (Math.random() * 360).toFloat()
                    if (!loc.hasSpeed()) loc.speed = (0.2 + Math.random() * 0.3).toFloat()
                    try {
                        loc.verticalAccuracyMeters = 8.0f + (Math.random() * 4).toFloat()
                        loc.bearingAccuracyDegrees = 10.0f + (Math.random() * 5).toFloat()
                        loc.speedAccuracyMetersPerSecond = 0.5f + (Math.random() * 0.5).toFloat()
                    } catch (_: Throwable) {}
                    // Satellite count in extras Bundle — apps read this
                    val extras = loc.extras ?: Bundle()
                    extras.putInt("satellites", 12)
                    extras.putInt("satellitesInFix", 10)
                    extras.remove("mockProvider")
                    loc.extras = extras
                    locMgr.setTestProviderLocation(p, loc)
                } catch (e: Throwable) {
                    log("[SYS-ML] setTestProviderLocation($p) ERR: ${e.javaClass.simpleName}: ${e.message}")
                    // Auto-recover: re-add test provider if removed
                    if (e.message?.contains("not a test provider") == true) {
                        try {
                            locMgr.addTestProvider(p, false, false, false, false, true, true, true, 1, 1)
                            locMgr.setTestProviderEnabled(p, true)
                        } catch (_: Throwable) {}
                    }
                }
            }
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
    }

    /**
     * Continuous loop in system_server that refreshes test provider locations
     * every 800ms. Prevents location staleness when FL app is throttled in background.
     * Also auto-recovers if test providers get removed unexpectedly.
     */
    private fun startEnforcementLoop() {
        enforcementThread?.interrupt()
        enforcementThread = Thread {
            log("[SYS-ML] enforcement loop started")
            try {
                while (mocking && !Thread.interrupted()) {
                    try {
                        val loc = currentLocation
                        if (loc != null) pushLocation(loc)
                    } catch (e: Throwable) {
                        log("[SYS-ML] enforcement push error: ${e.message}")
                    }
                    Thread.sleep(800)
                }
            } catch (_: InterruptedException) {}
            log("[SYS-ML] enforcement loop stopped")
        }.apply {
            name = "FL-MockEnforce"
            isDaemon = true
            start()
        }
    }
}

// ============================================================
//  Mock Location Service registration — extension on ModuleMain
// ============================================================
internal fun ModuleMain.registerMockLocationService() {
    try {
        val ctx = getSystemServerContext()
        if (ctx == null) {
            log("[SYS-ML] Cannot get system context for service_fl_ml")
            return
        }
        val binder = MockLocationBinder(ctx, bypassRemoveProvider)
        val smClass = Class.forName("android.os.ServiceManager")

        // Try multiple addService overloads — Android versions differ:
        // API <28: addService(String, IBinder)
        // API 28+: addService(String, IBinder, boolean) — allowIsolated
        // API 33+: addService(String, IBinder, boolean, int) — dumpPriority
        var registered = false

        // Try 4-arg first (newest API)
        if (!registered) {
            try {
                smClass.getMethod("addService", String::class.java, IBinder::class.java,
                    Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(null, "service_fl_ml", binder, false, 0)
                registered = true
                log("[SYS-ML] registered via addService(String,IBinder,boolean,int)")
            } catch (_: NoSuchMethodException) {}
        }
        // Try 3-arg
        if (!registered) {
            try {
                smClass.getMethod("addService", String::class.java, IBinder::class.java,
                    Boolean::class.javaPrimitiveType)
                    .invoke(null, "service_fl_ml", binder, false)
                registered = true
                log("[SYS-ML] registered via addService(String,IBinder,boolean)")
            } catch (_: NoSuchMethodException) {}
        }
        // Try 2-arg (legacy)
        if (!registered) {
            smClass.getMethod("addService", String::class.java, IBinder::class.java)
                .invoke(null, "service_fl_ml", binder)
            registered = true
            log("[SYS-ML] registered via addService(String,IBinder)")
        }

        // Verify registration actually worked
        val check = smClass.getMethod("getService", String::class.java)
            .invoke(null, "service_fl_ml") as? IBinder
        if (check != null) {
            ourMlBinder = binder
            log("[SYS-ML] service_fl_ml verified in ServiceManager!")
        } else {
            log("[SYS-ML] service_fl_ml addService succeeded but getService returned null — SELinux or other issue")
        }
    } catch (e: Throwable) {
        log("[SYS-ML] service_fl_ml registration FAILED: $e")
    }
}

internal fun ModuleMain.getSystemServerContext(): Context? {
    return try {
        val at = Class.forName("android.app.ActivityThread")
            .getMethod("currentActivityThread").invoke(null)
        at?.javaClass?.getMethod("getSystemContext")?.invoke(at) as? Context
    } catch (e: Throwable) {
        log("[SYS-ML] getSystemServerContext: $e")
        null
    }
}

/**
 * Create MockLocationBinder early without requiring ServiceManager.addService.
 * The virtual hook in SelinuxPolicy.kt intercepts getService("service_fl_ml")
 * and returns this binder directly, bypassing native SELinux checks.
 */
internal fun ModuleMain.earlyCreateMockLocationBinder() {
    if (ourMlBinder != null) return
    try {
        val ctx = getSystemServerContext() ?: return
        val binder = MockLocationBinder(ctx, bypassRemoveProvider)
        ourMlBinder = binder
        log("[SYS-ML] MockLocationBinder created (virtual — no ServiceManager.addService needed)")

        // Also try real registration in background — keeps retrying until SELinux is Permissive
        // (FL app process will `su setenforce 0` when it starts)
        Thread {
            try {
                val smClass = Class.forName("android.os.ServiceManager")
                var registered = false
                var secs = 0
                // Retry indefinitely — FL app may not start for several minutes
                while (!registered) {
                    Thread.sleep(2000)
                    secs += 2
                    if (secs % 30 == 0) log("[SYS-ML] FL-RealReg: still retrying (${secs}s)")
                    // Try addService — will fail with SecurityException until SELinux is Permissive
                    try {
                        smClass.getMethod("addService", String::class.java, android.os.IBinder::class.java,
                            Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                            .invoke(null, "service_fl_ml", binder, false, 0)
                        // Verify the registration actually worked via native servicemanager
                        val check = smClass.getMethod("getService", String::class.java)
                            .invoke(null, "service_fl_ml") as? android.os.IBinder
                        if (check != null) {
                            registered = true
                            log("[SYS-ML] FL-RealReg: registered + verified at ${secs}s")
                        } else {
                            log("[SYS-ML] FL-RealReg: addService returned OK but getService=null at ${secs}s")
                        }
                    } catch (e: Throwable) {
                        val cause = if (e is java.lang.reflect.InvocationTargetException) e.targetException else e
                        if (cause is SecurityException) {
                            if (secs % 30 == 0) log("[SYS-ML] FL-RealReg: SELinux blocked (${secs}s)")
                        } else {
                            // Try 3-arg fallback
                            try {
                                smClass.getMethod("addService", String::class.java, android.os.IBinder::class.java,
                                    Boolean::class.javaPrimitiveType)
                                    .invoke(null, "service_fl_ml", binder, false)
                                val check = smClass.getMethod("getService", String::class.java)
                                    .invoke(null, "service_fl_ml") as? android.os.IBinder
                                if (check != null) {
                                    registered = true
                                    log("[SYS-ML] FL-RealReg: registered (3-arg) + verified at ${secs}s")
                                } else {
                                    log("[SYS-ML] FL-RealReg: 3-arg OK but getService=null at ${secs}s")
                                }
                            } catch (e2: Throwable) {
                                val c2 = if (e2 is java.lang.reflect.InvocationTargetException) e2.targetException else e2
                                if (secs % 10 == 0) log("[SYS-ML] FL-RealReg: ${cause.javaClass.simpleName}/${c2.javaClass.simpleName} (${secs}s)")
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                log("[SYS-ML] FL-RealReg: thread error: $e")
            }
        }.apply { name = "FL-RealReg"; isDaemon = true; start() }
    } catch (e: Throwable) {
        log("[SYS-ML] earlyCreateMockLocationBinder failed: $e")
    }
}
