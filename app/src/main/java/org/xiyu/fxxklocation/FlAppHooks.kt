package org.xiyu.fxxklocation

import android.content.Context
import android.location.Location
import android.os.IBinder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Modifier

// ============================================================
//  LAYER 1: FL app hooks (Mode 0: app-side + Binder poison)
// ============================================================
internal fun ModuleMain.performFlHooks(cl: ClassLoader) {
    patchSelinuxAsync()

    safeHook("AntiXposedDetect") { hookAntiXposedDetect(cl) }
    safeHook("BlacklistManager") { hookBlacklistManager(cl) }
    safeHook("BlacklistTransport") { hookBlacklistTransport(cl) }
    safeHook("EnforcementBypass") { hookEnforcementBypass(cl) }
    safeHook("StopMockBlock") { hookStopMockBlock(cl) }
    safeHook("DexLoaderDebug") { hookDexLoaderDebug(cl) }
    safeHook("DisabledLists") { hookDisabledLists(cl) }
    safeHook("TokenVerifier") { hookTokenVerifier(cl) }
    safeHook("ProValidator") { hookProValidator(cl) }
    safeHook("UserSession") { hookUserSession(cl) }
    safeHook("UserModel") { hookUserModel(cl) }
    safeHook("XpModeEnable") { hookXpModeEnable(cl) }
    safeHook("Mode0Binder") { hookMode0Binder(cl) }
    safeHook("AgreementDialog") { hookAgreementDialog(cl) }
    safeHook("UpdateDialog") { hookUpdateDialog(cl) }
    log("[FL] All hooks installed (v41: proactive service_fl_ml + compat fixes + SELinux + agreement + step + enforcement + mock flag strip + Mode0Binder)")

    triggerJedDexLoading(cl)
    sendAddXpServiceBroadcast(cl)
}

// ==================== Agreement Dialog Block (gc2) ====================
private fun ModuleMain.hookAgreementDialog(cl: ClassLoader) {
    val gc2 = XposedHelpers.findClass("androidx.appcompat.view.widget.gc2", cl)
    val targetMethod = gc2.declaredMethods.firstOrNull { m ->
        Modifier.isStatic(m.modifiers)
            && m.returnType == Void.TYPE
            && m.parameterTypes.size == 1
            && m.parameterTypes[0] == Context::class.java
    }
    if (targetMethod == null) {
        log("[FL] AgreementDialog: no matching static void(Context) in gc2")
        return
    }
    XposedBridge.hookMethod(targetMethod, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val ctx = param.args[0] as Context
            val prefs = ctx.getSharedPreferences("ua", 0)
            if (!prefs.getBoolean("agreed", false) || prefs.getString("id", null).isNullOrEmpty()) {
                prefs.edit()
                    .putBoolean("agreed", true)
                    .putInt("local_version", 9999)
                    .putString("id", "hooked")
                    .apply()
                log("[FL] Agreement dialog blocked, marked as agreed")
            }
            param.result = null
        }
    })
    log("[FL] AgreementDialog: hooked ${targetMethod.name}(Context)")
}

// ==================== Update Dialog Block ====================
private fun ModuleMain.hookUpdateDialog(cl: ClassLoader) {
    // TODO: 请将 "com.lerist.common.version.C11159" 替换为实际的更新弹窗类名，如果也是某个混淆名称比如 "gc3"，请替换。
    val updateDialogClass = try {
        XposedHelpers.findClass("com.lerist.common.version.C11159", cl)
    } catch (e: Exception) {
        log("[FL] UpdateDialog: class not found")
        return
    }
    
    val targetMethod = updateDialogClass.declaredMethods.firstOrNull { m ->
        Modifier.isStatic(m.modifiers)
            && m.returnType == Void.TYPE
            && m.parameterTypes.size == 1
            && m.parameterTypes[0] == Context::class.java
    }
    
    if (targetMethod == null) {
        log("[FL] UpdateDialog: no matching static void(Context) in target class")
        return
    }
    
    XposedBridge.hookMethod(targetMethod, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            // 直接将结果置空，使得原本显示弹窗的方法直接返回，达到强制关闭/不显示的效果
            param.result = null
            log("[FL] Update dialog blocked")
        }
    })
    log("[FL] UpdateDialog: hooked ${targetMethod.name}(Context)")
}

// ==================== XP Mode Enable (ap2 + C5039) ====================
private fun ModuleMain.hookXpModeEnable(cl: ClassLoader) {
    var hooked = 0
    try {
        val ap2Cls = cl.loadClass(CLS_XP_AP2)
        for (m in ap2Cls.declaredMethods) {
            if (!Modifier.isStatic(m.modifiers)
                && m.returnType == Boolean::class.javaPrimitiveType
                && m.parameterTypes.size == 1
                && m.parameterTypes[0] == Context::class.java
            ) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = true
                        log("[FL] XP-MODE: ap2.m642() → true (enable XP)")
                    }
                })
                hooked++
            }
        }
    } catch (e: Throwable) { log("[FL] XP-MODE ap2 FAILED: ${e.message}") }

    try {
        val c5039Cls = cl.loadClass(CLS_XP_READY)
        val holderCls = c5039Cls.declaredClasses.firstOrNull { cls ->
            cls.declaredFields.any { it.type == c5039Cls && Modifier.isStatic(it.modifiers) }
        }
        if (holderCls != null) {
            val singletonField = holderCls.declaredFields.first {
                it.type == c5039Cls && Modifier.isStatic(it.modifiers)
            }
            singletonField.isAccessible = true
            val singleton = singletonField.get(null)
            if (singleton != null) {
                for (f in c5039Cls.declaredFields) {
                    if (!Modifier.isStatic(f.modifiers) && f.type == Boolean::class.javaPrimitiveType) {
                        f.isAccessible = true
                        f.setBoolean(singleton, true)
                        hooked++
                        log("[FL] XP-MODE: C5039.${f.name} = true")
                    }
                }
            }
        }
        for (m in c5039Cls.declaredMethods) {
            if (!Modifier.isStatic(m.modifiers)
                && m.returnType == Boolean::class.javaPrimitiveType
                && m.parameterTypes.isEmpty()
            ) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = true
                    }
                })
                hooked++
            }
        }
    } catch (e: Throwable) { log("[FL] XP-MODE C5039 FAILED: ${e.message}") }
    log("[FL] XP-MODE: $hooked hooks/fields set")
}

// ==================== Mode0Binder proxy injection ====================
private fun ModuleMain.hookMode0Binder(cl: ClassLoader) {
    val mode0Cls = try { cl.loadClass(CLS_MODE0_BINDER) } catch (e: Throwable) {
        log("[FL] MODE0: class not found: ${e.message}"); return
    }
    var hooked = 0

    val proxyGetterMethod = mode0Cls.declaredMethods.firstOrNull { m ->
        !Modifier.isStatic(m.modifiers) && m.parameterTypes.isEmpty()
            && m.returnType.isInterface
            && android.os.IInterface::class.java.isAssignableFrom(m.returnType)
    }
    val aidlIfaceType = proxyGetterMethod?.returnType
    var asIfaceMethod: java.lang.reflect.Method? = null
    if (aidlIfaceType != null) {
        for (innerCls in aidlIfaceType.declaredClasses) {
            for (m in innerCls.declaredMethods) {
                if (Modifier.isStatic(m.modifiers)
                    && m.parameterTypes.size == 1
                    && m.parameterTypes[0] == IBinder::class.java
                    && aidlIfaceType.isAssignableFrom(m.returnType)
                ) {
                    asIfaceMethod = m
                    break
                }
            }
            if (asIfaceMethod != null) break
        }
    }
    val proxyField = if (aidlIfaceType != null) {
        mode0Cls.declaredFields.firstOrNull {
            !Modifier.isStatic(it.modifiers) && it.type == aidlIfaceType
        }?.apply { isAccessible = true }
    } else null

    log("[FL] MODE0: aidlIface=${aidlIfaceType?.name} asInterface=${asIfaceMethod?.name} proxyField=${proxyField?.name}")

    fun tryConnectServiceFlMl(instance: Any, blocking: Boolean): Any? {
        val smClass = Class.forName("android.os.ServiceManager")
        val getServiceMethod = smClass.getMethod("getService", String::class.java)
        val maxAttempts = if (blocking) 60 else 1
        for (attempt in 1..maxAttempts) {
            val binder = getServiceMethod.invoke(null, "service_fl_ml") as? IBinder
            if (binder != null && asIfaceMethod != null) {
                val proxy = asIfaceMethod.invoke(null, binder)
                if (proxy != null) {
                    proxyField?.set(instance, proxy)
                    log("[FL] MODE0: connected to service_fl_ml (attempt $attempt, blocking=$blocking)")
                    return proxy
                }
            }
            if (attempt < maxAttempts) Thread.sleep(500)
        }
        if (blocking) log("[FL] MODE0: service_fl_ml not found after 30s polling")
        return null
    }

    // Background thread to pre-cache proxy
    Thread {
        try {
            Thread.sleep(3000)
            val smClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = smClass.getMethod("getService", String::class.java)
            val holderCls = mode0Cls.declaredClasses.firstOrNull { cls ->
                cls.declaredFields.any { it.type == mode0Cls && Modifier.isStatic(it.modifiers) }
            }
            val instanceField = holderCls?.declaredFields?.firstOrNull {
                it.type == mode0Cls && Modifier.isStatic(it.modifiers)
            }
            instanceField?.isAccessible = true
            val instance = instanceField?.get(null)
            if (instance == null) {
                log("[FL] MODE0-BG: cannot get singleton instance"); return@Thread
            }
            for (attempt in 1..600) {
                val binder = getServiceMethod.invoke(null, "service_fl_ml") as? IBinder
                if (binder != null && asIfaceMethod != null) {
                    val proxy = asIfaceMethod.invoke(null, binder)
                    if (proxy != null) {
                        proxyField?.set(instance, proxy)
                        log("[FL] MODE0-BG: pre-cached proxy (${attempt}×500ms)")
                        return@Thread
                    }
                }
                Thread.sleep(500)
            }
            log("[FL] MODE0-BG: service_fl_ml not found after 300s")
        } catch (e: Throwable) {
            log("[FL] MODE0-BG: error: $e")
        }
    }.apply { name = "FL-Mode0Preconnect"; isDaemon = true }.start()

    for (m in mode0Cls.declaredMethods) {
        if (Modifier.isStatic(m.modifiers)) continue

        if (m == proxyGetterMethod) {
            m.isAccessible = true
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.result != null) return
                    try {
                        val proxy = tryConnectServiceFlMl(param.thisObject, false)
                        if (proxy != null) {
                            param.result = proxy
                            return
                        }
                        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                            val retryProxy = tryConnectServiceFlMl(param.thisObject, true)
                            if (retryProxy != null) param.result = retryProxy
                        }
                    } catch (e: Throwable) {
                        log("[FL] MODE0: getProxy poll failed: $e")
                    }
                }
            })
            hooked++
            log("[FL] MODE0: hooked getProxy() = ${m.name}")
        }

        if (m.parameterTypes.isEmpty() && m.returnType == Boolean::class.javaPrimitiveType) {
            m.isAccessible = true
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.result == true) return
                    try {
                        val smClass = Class.forName("android.os.ServiceManager")
                        val binder = smClass.getMethod("getService", String::class.java)
                            .invoke(null, "service_fl_ml") as? IBinder
                        if (binder != null) {
                            param.result = true
                            log("[FL] MODE0: isConnected() → true")
                        }
                    } catch (_: Throwable) {}
                }
            })
            hooked++
            log("[FL] MODE0: hooked isConnected() = ${m.name}")
        }
    }
    log("[FL] MODE0: $hooked hooks installed")
}

private fun ModuleMain.patchSelinuxAsync() {
    Thread {
        try {
            Thread.sleep(500)
            val success = applySELinuxPolicy()
            if (!success) {
                log("[FL] SELinux policy patch failed — service connection may fail")
            }
            // Enable sensor data injection at HAL level via root
            // This allows system_server's SensorManager.injectSensorData() to work
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "service call sensorservice 3 i32 1"))
                val exit = p.waitFor()
                log("[FL] sensorservice data injection enable: exit=$exit")
            } catch (e: Throwable) {
                log("[FL] sensorservice enable failed: $e")
            }
        } catch (e: Throwable) {
            log("[FL] patchSelinuxAsync error: $e")
        }
    }.apply { name = "FL-SEPolicy"; isDaemon = true }.start()
}

// ==================== DexLoader debug tracing ====================
private fun ModuleMain.hookDexLoaderDebug(cl: ClassLoader) {
    var hookCount = 0
    val loaderCls = try { cl.loadClass(CLS_ENF_LOADER) } catch (_: Exception) {
        log("[FL] DEX-DBG: DexLoader class not found"); return
    }
    for (m in loaderCls.declaredMethods) {
        if (!Modifier.isStatic(m.modifiers) || m.returnType != Void.TYPE) continue
        val params = m.parameterTypes
        if (params.size == 2 && params[0] == Context::class.java && params[1] == String::class.java) {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = (param.args[1] as? String)?.take(30) ?: "null"
                    log("[FL] DEX-DBG: loadDynamicDex CALLED key=$key")
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) {
                        log("[FL] DEX-DBG: loadDynamicDex EXCEPTION: ${param.throwable}")
                    } else {
                        log("[FL] DEX-DBG: loadDynamicDex returned normally")
                    }
                }
            })
            hookCount++
        }
        if (params.size == 3 && params[0] == Context::class.java
            && params[1] == String::class.java && params[2] == String::class.java) {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val data = (param.args[2] as? String)
                    log("[FL] DEX-DBG: loadDynamicDexInner CALLED dataLen=${data?.length ?: -1}")
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) {
                        log("[FL] DEX-DBG: loadDynamicDexInner EXCEPTION: ${param.throwable}")
                    } else {
                        log("[FL] DEX-DBG: loadDynamicDexInner completed")
                    }
                }
            })
            hookCount++
        }
    }
    try {
        val jedBlCls = cl.loadClass(CLS_JED_BL)
        for (m in jedBlCls.declaredMethods) {
            if (Modifier.isStatic(m.modifiers)) continue
            if (m.returnType == String::class.java && m.parameterTypes.isEmpty()
                && m.name != "toString") {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val result = param.result as? String
                        val desc = if (result.isNullOrBlank()) "EMPTY" else "${result.length}chars"
                        log("[FL] DEX-DBG: JedBL.${m.name}() => $desc")
                    }
                })
                hookCount++
            }
        }
    } catch (_: Exception) { log("[FL] DEX-DBG: JedBlacklist class not found") }
    log("[FL] DEX-DBG: $hookCount debug hooks installed")
}

private fun ModuleMain.triggerJedDexLoading(cl: ClassLoader) {
    Thread {
        try {
            Thread.sleep(5000)
            log("[FL] JED-TRIGGER: forcing BlacklistManager class init...")
            Class.forName(CLS_BLACKLIST, true, cl)
            log("[FL] JED-TRIGGER: BlacklistManager initialized (jed dex should load)")
        } catch (e: Throwable) {
            log("[FL] JED-TRIGGER: failed: $e")
        }
    }.apply { name = "FL-JedTrigger"; isDaemon = true }.start()
}

private fun ModuleMain.sendAddXpServiceBroadcast(cl: ClassLoader) {
    Thread {
        try {
            Thread.sleep(8000)
            val ctx = try {
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as? android.content.Context
            } catch (_: Throwable) { null }
            if (ctx == null) {
                log("[FL] Could not get FL app context for broadcast"); return@Thread
            }
            val action = "com.lerist.fakelocation.action.ADD_XP_SERVICE"
            applySELinuxPolicy()
            for (i in 1..3) {
                ctx.sendBroadcast(android.content.Intent(action))
                log("[FL] Sent ADD_XP_SERVICE broadcast #$i from FL app")
                Thread.sleep(2000)
            }
        } catch (e: Throwable) {
            log("[FL] ADD_XP_SERVICE broadcast from FL failed: $e")
        }
    }.apply { name = "FL-XpBroadcast"; isDaemon = true }.start()
}

internal inline fun safeHook(name: String, block: () -> Unit) {
    try { block() } catch (e: Throwable) { log("[FL] SAFE-FAIL $name: ${e.message}") }
}

// ==================== Anti-Xposed detection bypass (cp2 + C5617) ====================
private fun ModuleMain.hookAntiXposedDetect(cl: ClassLoader) {
    var hooked = 0
    try {
        val cp2Cls = cl.loadClass(CLS_DEBUG_DETECT)
        for (m in cp2Cls.declaredMethods) {
            if (Modifier.isStatic(m.modifiers) && m.returnType == Boolean::class.javaPrimitiveType && m.parameterTypes.isEmpty()) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = false
                    }
                })
                hooked++
            }
            if (Modifier.isStatic(m.modifiers) && m.returnType == Void.TYPE
                && m.parameterTypes.size == 1 && m.parameterTypes[0] == Throwable::class.java) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = null
                    }
                })
                hooked++
            }
        }
        try {
            val f = cp2Cls.getDeclaredField("f2117")
            f.isAccessible = true
            f.setBoolean(null, false)
            hooked++
        } catch (_: Throwable) {}
    } catch (e: Throwable) { log("[FL] DETECT cp2 FAILED: ${e.message}") }

    try {
        val antiCls = cl.loadClass(CLS_ANTI_DEBUG)
        for (m in antiCls.declaredMethods) {
            if (Modifier.isStatic(m.modifiers) && m.returnType == Void.TYPE && m.parameterTypes.isEmpty()) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = null
                    }
                })
                hooked++
            }
        }
    } catch (e: Throwable) { log("[FL] DETECT C5617 FAILED: ${e.message}") }
    log("[FL] AntiXposedDetect: $hooked hooks installed")
}

// ==================== Blacklist UI bypass ====================
private fun ModuleMain.hookBlacklistManager(cl: ClassLoader) {
    val blCls: Class<*>
    val cfgCls: Class<*>
    try {
        blCls = cl.loadClass(CLS_BLACKLIST)
        cfgCls = cl.loadClass(CLS_CONFIG)
    } catch (e: ClassNotFoundException) {
        log("[FL] BL: class NOT FOUND: ${e.message}"); return
    }

    var hooked = 0
    for (m in blCls.declaredMethods) {
        if (!Modifier.isStatic(m.modifiers)) continue
        when {
            m.returnType == Boolean::class.javaPrimitiveType
                && m.parameterTypes.size == 1
                && m.parameterTypes[0] == String::class.java -> {
                XposedBridge.hookMethod(m, hookReturnTrue); hooked++
            }
            m.returnType == Boolean::class.javaPrimitiveType
                && m.parameterTypes.size == 2
                && m.parameterTypes[0] == String::class.java
                && m.parameterTypes[1] == Boolean::class.javaPrimitiveType -> {
                XposedBridge.hookMethod(m, hookReturnTrue); hooked++
            }
            m.returnType == Void.TYPE
                && m.parameterTypes.size == 1
                && m.parameterTypes[0] == cfgCls -> {
                XposedBridge.hookMethod(m, hookNeutralizeConfig); hooked++
            }
        }
    }
    log("[FL] BL-UI: $hooked methods hooked")
}

// ==================== Blacklist transport ====================
private fun ModuleMain.hookBlacklistTransport(cl: ClassLoader) {
    try {
        val svcCls = cl.loadClass(CLS_SERVICE)
        val listMethods = svcCls.declaredMethods.filter { m ->
            !Modifier.isStatic(m.modifiers)
                && m.returnType == Void.TYPE
                && m.parameterTypes.size == 1
                && m.parameterTypes[0] == List::class.java
        }
        var svcHooked = 0
        for (m in listMethods) {
            var isStringList = false
            try {
                val genType = m.genericParameterTypes[0]
                if (genType is java.lang.reflect.ParameterizedType) {
                    isStringList = genType.actualTypeArguments.firstOrNull() == String::class.java
                }
            } catch (_: Throwable) {}
            if (!isStringList) {
                log("[FL] TRANSPORT-svc: SKIP ${m.name} (not List<String>)")
                continue
            }
            m.isAccessible = true
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val orig = param.args[0] as? List<*>
                    val strList = orig?.mapNotNull { it as? String }
                    val filtered = filterBlacklist(strList)
                    param.args[0] = ArrayList(filtered)
                    log("[FL] TRANSPORT-svc: ${(param.method as java.lang.reflect.Method).name} -> filtered (was ${orig?.size ?: 0} -> ${filtered.size})")
                }
            })
            svcHooked++
        }
        log("[FL] TRANSPORT-svc: $svcHooked methods hooked (of ${listMethods.size} candidates)")
    } catch (e: Exception) {
        log("[FL] TRANSPORT-svc FAILED: ${e.message}")
    }

    try {
        val defCls = cl.loadClass(CLS_DEFAULTS)
        val m14591 = defCls.declaredMethods.firstOrNull { m ->
            Modifier.isStatic(m.modifiers)
                && m.returnType == ArrayList::class.java
                && m.parameterTypes.isEmpty()
        }
        if (m14591 != null) {
            XposedBridge.hookMethod(m14591, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = ArrayList<String>()
                }
            })
            log("[FL] TRANSPORT-defaults: C3131 -> empty")
        }
    } catch (e: Exception) {
        log("[FL] TRANSPORT-defaults FAILED: ${e.message}")
    }
}

// ==================== Enforcement bypass (C2553 app-side) ====================
private fun ModuleMain.hookEnforcementBypass(cl: ClassLoader) {
    val enfCls = try { cl.loadClass(CLS_ENFORCER) } catch (_: Exception) {
        log("[FL] ENFORCE: NOT FOUND"); return
    }
    var hooked = 0
    for (m in enfCls.declaredMethods) {
        if (Modifier.isStatic(m.modifiers)) continue
        if (m.returnType == Boolean::class.javaPrimitiveType
            && m.parameterTypes.size == 1
            && m.parameterTypes[0] == String::class.java
        ) {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = true
                }
            })
            hooked++; log("[FL] ENFORCE: ${m.name}(String)->true")
        }
        if (m.returnType == Void.TYPE
            && m.parameterTypes.size == 1
            && m.parameterTypes[0] == Location::class.java
        ) {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null
                }
            })
            hooked++; log("[FL] ENFORCE: ${m.name}(Location)->noop")
        }
    }
    log("[FL] ENFORCE: $hooked methods")
}

// ==================== Enforcement guard ====================
private fun ModuleMain.hookStopMockBlock(cl: ClassLoader) {
    try {
        val enfCls = cl.loadClass(CLS_ENFORCER)
        for (m in enfCls.declaredMethods) {
            if (m.returnType == Boolean::class.javaPrimitiveType
                && m.parameterTypes.size == 1
                && m.parameterTypes[0] == String::class.java
                && !Modifier.isStatic(m.modifiers)
            ) {
                log("[FL] GUARD: checkForegroundAllowed already hooked via enforcementBypass")
            }
        }
    } catch (e: Exception) {
        log("[FL] GUARD: ${e.message}")
    }
    log("[FL] GUARD: pause() NOT blocked (user control allowed)")
}

// ==================== DexLoader block (ft0) ====================
@Suppress("unused")
private fun ModuleMain.hookDexLoader(cl: ClassLoader) {
    val loaderCls = try { cl.loadClass(CLS_ENF_LOADER) } catch (_: Exception) {
        log("[FL] DexLoader NOT FOUND"); return
    }
    var hooked = 0
    for (m in loaderCls.declaredMethods) {
        if (!Modifier.isStatic(m.modifiers) || m.returnType != Void.TYPE) continue
        val params = m.parameterTypes
        if ((params.size == 2 && params[0] == Context::class.java && params[1] == String::class.java) ||
            (params.size == 3 && params[0] == Context::class.java && params[1] == String::class.java && params[2] == String::class.java)) {
            m.isAccessible = true
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null
                    log("[FL] DEX-BLOCK: ${m.name}(${params.size} args) → noop")
                }
            })
            hooked++
        }
    }
    log("[FL] DexLoader: $hooked methods blocked")
}

// ==================== Disabled lists bypass ====================
private fun ModuleMain.hookDisabledLists(cl: ClassLoader) {
    val blCls = try { cl.loadClass(CLS_BLACKLIST) } catch (_: Exception) {
        log("[FL] DisabledLists: NOT FOUND"); return
    }
    var hooked = 0
    for (m in blCls.declaredMethods) {
        if (!Modifier.isStatic(m.modifiers)) continue
        if (m.parameterTypes.isNotEmpty()) continue
        if (List::class.java.isAssignableFrom(m.returnType)) {
            m.isAccessible = true
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val orig = param.result as? List<*>
                    if (orig != null) {
                        val strList = orig.mapNotNull { it as? String }
                        param.result = ArrayList(filterBlacklist(strList))
                    }
                }
            })
            hooked++
        }
    }
    log("[FL] DisabledLists: $hooked static List() methods → empty")
}

// ==================== Token verifier block (tc2) ====================
private fun ModuleMain.hookTokenVerifier(cl: ClassLoader) {
    val tc2Cls = try { cl.loadClass(CLS_TOKEN_CHECKER) } catch (_: Exception) {
        log("[FL] TokenVerifier NOT FOUND"); return
    }
    var hooked = 0
    for (m in tc2Cls.declaredMethods) {
        if (Modifier.isStatic(m.modifiers)
            && m.returnType == Void.TYPE
            && m.parameterTypes.size == 1
            && m.parameterTypes[0].isInterface
        ) {
            m.isAccessible = true
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null
                    log("[FL] TOKEN-CHECK: blocked server validation")
                    try {
                        val cb = param.args[0] ?: return
                        for (cbm in cb.javaClass.declaredMethods) {
                            if (cbm.name == "onFinish" && cbm.parameterTypes.isEmpty()) {
                                cbm.isAccessible = true
                                cbm.invoke(cb)
                                break
                            }
                        }
                    } catch (_: Throwable) {}
                }
            })
            hooked++
        }
    }
    log("[FL] TokenVerifier: $hooked methods blocked")
}

// ==================== Pro validator bypass (pc2) ====================
private fun ModuleMain.hookProValidator(cl: ClassLoader) {
    val pc2Cls = try { cl.loadClass(CLS_PRO_VALIDATOR) } catch (_: Exception) {
        log("[FL] ProValidator NOT FOUND"); return
    }
    var hooked = 0
    for (m in pc2Cls.declaredMethods) {
        if (!Modifier.isStatic(m.modifiers)) continue
        m.isAccessible = true
        when {
            m.returnType == Long::class.javaPrimitiveType && m.parameterTypes.isEmpty() -> {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = Long.MAX_VALUE
                        log("[FL] PRO-VAL: ${m.name}() → MAX_VALUE")
                    }
                })
                hooked++
            }
            m.returnType == String::class.java && m.parameterTypes.isEmpty() -> {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = "fxxk_pro"
                    }
                })
                hooked++
            }
        }
    }
    try {
        val cachedField = pc2Cls.getDeclaredField("f9497")
        cachedField.isAccessible = true
        cachedField.setBoolean(null, true)
        hooked++
    } catch (_: Throwable) {}
    try {
        val typeField = pc2Cls.getDeclaredField("f9496")
        typeField.isAccessible = true
        typeField.set(null, "fxxk_pro")
        hooked++
    } catch (_: Throwable) {}
    log("[FL] ProValidator: $hooked methods/fields hooked")
}

// ==================== User session (C2124) ====================
private fun ModuleMain.hookUserSession(cl: ClassLoader) {
    val sessCls = try { cl.loadClass(CLS_USER_SESSION) } catch (_: Exception) {
        log("[FL] Session NOT FOUND"); return
    }
    val userModelCls = try { cl.loadClass(CLS_USER_MODEL) } catch (_: Exception) { null }
    var hooked = 0
    for (m in sessCls.declaredMethods) {
        m.isAccessible = true
        when {
            m.returnType == Boolean::class.javaPrimitiveType && m.parameterTypes.isEmpty() -> {
                XposedBridge.hookMethod(m, hookReturnTrue); hooked++
            }
            m.returnType == Int::class.javaPrimitiveType && m.parameterTypes.isEmpty() -> {
                XposedBridge.hookMethod(m, hookReturnInt99); hooked++
            }
            m.returnType == Long::class.javaPrimitiveType && m.parameterTypes.isEmpty() -> {
                XposedBridge.hookMethod(m, hookReturnFutureLong); hooked++
            }
            m.returnType == String::class.java && m.parameterTypes.isEmpty() -> {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.result == null) param.result = "fxxk_dummy"
                    }
                }); hooked++
            }
            m.returnType == Void.TYPE && m.parameterTypes.size == 1
                && userModelCls != null && m.parameterTypes[0] == userModelCls -> {
                XposedBridge.hookMethod(m, hookPatchUserModel); hooked++
            }
            m.returnType == userModelCls && m.parameterTypes.isEmpty() -> {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.result == null && userModelCls != null) {
                            val model = userModelCls.newInstance()
                            patchUserModelFull(model)
                            param.result = model
                        } else if (param.result != null) {
                            patchUserModelFull(param.result!!)
                        }
                    }
                }); hooked++
            }
        }
    }
    log("[FL] Session: $hooked methods")
}

// ==================== User model (qc2) ====================
private fun ModuleMain.hookUserModel(cl: ClassLoader) {
    val qc2 = try { cl.loadClass(CLS_USER_MODEL) } catch (_: Exception) {
        log("[FL] Model NOT FOUND"); return
    }
    var hooked = 0
    for (m in qc2.declaredMethods) {
        when (m.name) {
            "getIdentity"  -> { XposedBridge.hookMethod(m, hookReturnInt99); hooked++ }
            "getType"      -> { XposedBridge.hookMethod(m, hookReturnInt1); hooked++ }
            "getProindate" -> { XposedBridge.hookMethod(m, hookReturnFutureLong); hooked++ }
            "getToken"     -> { XposedBridge.hookMethod(m, hookReturnDummyString); hooked++ }
            "getKey"       -> { XposedBridge.hookMethod(m, hookReturnDummyString); hooked++ }
            "getUserId"    -> { XposedBridge.hookMethod(m, hookReturnDummyString); hooked++ }
            "getUserName"  -> { XposedBridge.hookMethod(m, hookReturnProString); hooked++ }
        }
    }
    log("[FL] Model: $hooked methods")
}

// ==================== Shared callbacks ====================
internal val hookReturnTrue = object : XC_MethodHook() {
    override fun beforeHookedMethod(p: MethodHookParam) { p.result = true }
}
internal val hookReturnInt99 = object : XC_MethodHook() {
    override fun beforeHookedMethod(p: MethodHookParam) { p.result = 99 }
}
private val hookReturnInt1 = object : XC_MethodHook() {
    override fun beforeHookedMethod(p: MethodHookParam) { p.result = 1 }
}
internal val hookReturnFutureLong = object : XC_MethodHook() {
    override fun beforeHookedMethod(p: MethodHookParam) { p.result = 4070908800000L }
}
internal val hookNeutralizeConfig = object : XC_MethodHook() {
    override fun beforeHookedMethod(p: MethodHookParam) {
        val cfg = p.args[0] ?: return
        try {
            val clz = cfg.javaClass
            clz.getField("isAllowRun").setInt(cfg, 1)
            clz.getField("notice").set(cfg, "")
            clz.getField("disas").set(cfg, null)
            clz.getField("disfs").set(cfg, null)
            clz.getField("disis").set(cfg, null)
        } catch (_: Exception) {}
    }
}
internal val hookPatchUserModel = object : XC_MethodHook() {
    override fun beforeHookedMethod(p: MethodHookParam) {
        val u = p.args[0]
        if (u == null) {
            p.result = null
            log("[FL] BLOCKED setUserModel(null) — preventing logout")
            return
        }
        patchUserModelFull(u)
    }
}
private val hookReturnDummyString = object : XC_MethodHook() {
    override fun beforeHookedMethod(p: MethodHookParam) { p.result = "fxxk_dummy" }
}
private val hookReturnProString = object : XC_MethodHook() {
    override fun beforeHookedMethod(p: MethodHookParam) { p.result = "Pro" }
}

internal fun patchUserModelFull(u: Any) {
    try {
        val clz = u.javaClass
        clz.getField("identity").setInt(u, 99)
        clz.getField("type").setInt(u, 1)
        clz.getField("proindate").setLong(u, 4070908800000L)
        clz.getField("token").set(u, "fxxk_pro_token")
        clz.getField("key").set(u, "fxxk_pro_key")
    } catch (_: Exception) {}
    try {
        val clz = u.javaClass
        clz.getDeclaredField("userId").apply { isAccessible = true }.set(u, "fxxk_user")
        clz.getDeclaredField("userName").apply { isAccessible = true }.set(u, "Pro")
        clz.getDeclaredField("loginType").apply { isAccessible = true }.set(u, "xposed")
        clz.getDeclaredField("loginName").apply { isAccessible = true }.set(u, "FxxkLocation")
        clz.getDeclaredField("loginTime").apply { isAccessible = true }.setLong(u, System.currentTimeMillis())
    } catch (_: Exception) {}
}
