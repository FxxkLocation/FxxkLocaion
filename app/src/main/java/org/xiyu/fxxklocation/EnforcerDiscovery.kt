package org.xiyu.fxxklocation

import android.location.Location
import android.os.IBinder
import android.os.Parcel
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType

// ============================================================
//  PRIMARY BYPASS: Hook binder stub's AIDL methods directly
// ============================================================
internal fun ModuleMain.hookBinderAidlMethods(binderCls: Class<*>) {
    var hookCount = 0

    // Find the AIDL interface (extends IInterface)
    val aidlIface = binderCls.interfaces.firstOrNull { iface ->
        android.os.IInterface::class.java.isAssignableFrom(iface)
    }
    log("[SYS-BINDER] AIDL interface: ${aidlIface?.name ?: "NOT FOUND"}")

    // Catch-all: hook onTransact for cross-process calls
    try {
        var targetTransact = binderCls
        var transactMethod = targetTransact.declaredMethods.firstOrNull { it.name == "onTransact" && it.parameterCount == 4 }

        while (transactMethod == null && targetTransact.superclass != null) {
            targetTransact = targetTransact.superclass as Class<*>
            transactMethod = targetTransact.declaredMethods.firstOrNull { it.name == "onTransact" && it.parameterCount == 4 }
        }

        if (transactMethod != null) {
            transactMethod.isAccessible = true
            XposedBridge.hookMethod(transactMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val code = param.args[0] as Int
                        if (code == 3) {
                            param.result = true
                            return
                        }
                        // Intentionally not intercepting 33/10 (getCloudBlacklist/getLocalBlacklist)
                        // so the filtered real list (synced during setCloudBlacklist via AppHooks) is returned properly.
                    } catch (_: Throwable) {}
                }
            })
            hookCount++
            log("[SYS-BINDER] onTransact hooked on class: ${targetTransact.name}")
        } else {
            log("[SYS-BINDER] onTransact not found in hierarchy")
        }
    } catch (e: Throwable) {
        log("[SYS-BINDER] onTransact hook failed: ${e.message}")
    }

    // Hook local direct methods safely by scanning method generic return types
    for (m in binderCls.declaredMethods) {
        if (m.parameterCount == 0 && List::class.java.isAssignableFrom(m.returnType)) {
            var isStringList = false
            try {
                val genType = m.genericReturnType
                if (genType is ParameterizedType) {
                    val typeArg = genType.actualTypeArguments.firstOrNull()
                    if (typeArg == String::class.java) isStringList = true
                } else {
                    // Erased generics — DO NOT hook (could be List<CellInfo>/List<SubscriptionInfo>)
                    isStringList = false
                }
            } catch (e: Throwable) { isStringList = false /* Don't guess — could be List<CellInfo> */ }

            if (isStringList) {
                m.isAccessible = true
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try { param.result = ArrayList(filterBlacklist(param.result as? List<String>)) } catch (_: Throwable) {}
                    }
                })
                hookCount++
            }
        }
    }

    log("[SYS-BINDER] ====== $hookCount binder hooks installed ======")
}

// Try multiple strategies to find a ClassLoader that can load FL's classes
internal fun ModuleMain.tryFindFlClassLoader(binder: IBinder): ClassLoader? {
    val binderCl = binder.javaClass.classLoader

    // S1: From binder CL + parent chain
    var cl: ClassLoader? = binderCl
    while (cl != null) {
        try { cl.loadClass(CLS_ENFORCER); log("[SYS] S1: found in CL chain"); return cl }
        catch (_: ClassNotFoundException) {}
        cl = cl.parent
    }

    // S2: System ClassLoader
    try {
        val scl = ClassLoader.getSystemClassLoader()
        scl.loadClass(CLS_ENFORCER); log("[SYS] S2: system CL"); return scl
    } catch (_: ClassNotFoundException) {}

    // S3: Thread context ClassLoaders
    try {
        for (t in Thread.getAllStackTraces().keys) {
            val tcl = t.contextClassLoader ?: continue
            try { tcl.loadClass(CLS_ENFORCER); log("[SYS] S3: thread '${t.name}'"); return tcl }
            catch (_: ClassNotFoundException) {}
        }
    } catch (_: Throwable) {}

    // S4: Field traversal — find objects loaded by a different CL
    if (binderCl != null) {
        val testedCls = HashSet<Int>()
        testedCls.add(System.identityHashCode(binderCl))
        val visited = HashSet<Int>()

        fun scan(obj: Any, depth: Int): ClassLoader? {
            if (depth > 3) return null
            val id = System.identityHashCode(obj)
            if (!visited.add(id)) return null
            val ocl = obj.javaClass.classLoader
            if (ocl != null && testedCls.add(System.identityHashCode(ocl))) {
                try { ocl.loadClass(CLS_ENFORCER); return ocl }
                catch (_: ClassNotFoundException) {}
            }
            var c: Class<*>? = obj.javaClass
            while (c != null && c != Any::class.java && c != android.os.Binder::class.java) {
                for (f in c.declaredFields) {
                    if (f.type.isPrimitive) continue
                    try {
                        f.isAccessible = true
                        val v = if (Modifier.isStatic(f.modifiers)) f.get(null) else f.get(obj)
                        if (v != null) { scan(v, depth + 1)?.let { return it } }
                    } catch (_: Throwable) {}
                }
                c = c.superclass
            }
            return null
        }

        val found = scan(binder, 0)
        if (found != null) { log("[SYS] S4: field traversal"); return found }
    }

    log("[SYS] S1-S4 all failed: no ClassLoader can load CLS_ENFORCER by name")
    return null
}

// Find the enforcement class by structural signature in the binder's DEX
internal fun ModuleMain.findEnforcerByStructure(cl: ClassLoader): Class<*>? {
    try {
        val pathListField = findFieldInHierarchy(cl.javaClass, "pathList")
        if (pathListField == null) { log("[SYS] S5: not a BaseDexClassLoader"); return null }
        pathListField.isAccessible = true
        val pathList = pathListField.get(cl) ?: return null

        val dexElemsField = findFieldInHierarchy(pathList.javaClass, "dexElements")
            ?: return null
        dexElemsField.isAccessible = true
        val dexElements = dexElemsField.get(pathList) as? Array<*> ?: return null

        var total = 0
        for (elem in dexElements) {
            if (elem == null) continue
            val dexFileField = findFieldInHierarchy(elem.javaClass, "dexFile") ?: continue
            dexFileField.isAccessible = true
            val dexFile = dexFileField.get(elem) ?: continue
            val entriesMethod = dexFile.javaClass.getDeclaredMethod("entries")
            @Suppress("UNCHECKED_CAST")
            val entries = entriesMethod.invoke(dexFile) as java.util.Enumeration<String>
            val names = mutableListOf<String>()
            while (entries.hasMoreElements()) names.add(entries.nextElement())
            total += names.size
            log("[SYS] S5: DEX with ${names.size} classes")

            for (name in names) {
                try {
                    val cls = cl.loadClass(name)
                    if (matchesEnforcerSignature(cls)) {
                        log("[SYS] S5: FOUND enforcer by structure: $name")
                        return cls
                    }
                } catch (_: Throwable) {}
            }
        }
        log("[SYS] S5: scanned $total classes, no match")
    } catch (e: Throwable) {
        log("[SYS] S5 error: ${e.message}")
    }
    return null
}

// C2553-equivalent structural signature:
// non-static boolean(String) + void(Location) + void(List) + LocationManager field
internal fun matchesEnforcerSignature(cls: Class<*>): Boolean {
    val methods = cls.declaredMethods
    val hasCheck = methods.any {
        !Modifier.isStatic(it.modifiers)
            && it.returnType == Boolean::class.javaPrimitiveType
            && it.parameterTypes.let { p -> p.size == 1 && p[0] == String::class.java }
    }
    if (!hasCheck) return false
    val hasLoc = methods.any {
        !Modifier.isStatic(it.modifiers)
            && it.returnType == Void.TYPE
            && it.parameterTypes.let { p -> p.size == 1 && p[0] == Location::class.java }
    }
    if (!hasLoc) return false
    val hasList = methods.any {
        !Modifier.isStatic(it.modifiers)
            && it.returnType == Void.TYPE
            && it.parameterTypes.let { p -> p.size == 1 && p[0] == List::class.java }
    }
    if (!hasList) return false
    val hasLmField = cls.declaredFields.any {
        it.type == android.location.LocationManager::class.java
    }
    return hasLmField
}

internal fun findFieldInHierarchy(cls: Class<*>, name: String): java.lang.reflect.Field? {
    var c: Class<*>? = cls
    while (c != null) {
        try { return c.getDeclaredField(name) } catch (_: NoSuchFieldException) {}
        c = c.superclass
    }
    return null
}

// Hook the enforcer class found by structural matching
internal fun ModuleMain.hookEnforcerClass(enfCls: Class<*>) {
    var count = 0
    for (m in enfCls.declaredMethods) {
        if (Modifier.isStatic(m.modifiers)) continue
        if (m.returnType == Boolean::class.javaPrimitiveType
            && m.parameterTypes.let { it.size == 1 && it[0] == String::class.java }
        ) {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = true }
            })
            count++; log("[SYS] struct-hook: ${m.name}(String)->true")
        }
        if (m.returnType == Void.TYPE
            && m.parameterTypes.let { it.size == 1 && it[0] == Location::class.java }
        ) {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = null }
            })
            count++; log("[SYS] struct-hook: ${m.name}(Location)->noop")
        }
        if (m.returnType == Void.TYPE
            && m.parameterTypes.let { it.size == 1 && it[0] == List::class.java }
        ) {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val orig = param.args[0] as? List<*>
                    param.args[0] = ArrayList(filterBlacklist(orig?.mapNotNull { it as? String }))
                }
            })
            count++; log("[SYS] struct-hook: ${m.name}(List)->dummy")
        }
    }
    log("[SYS] ====== structural hooks installed: $count ======")
}

internal fun ModuleMain.installSystemServerHooks(cl: ClassLoader) {
    var count = 0
    try {
        val enfCls = cl.loadClass(CLS_ENFORCER)
        for (m in enfCls.declaredMethods) {
            if (Modifier.isStatic(m.modifiers)) continue
            if (m.returnType == Boolean::class.javaPrimitiveType
                && m.parameterTypes.let { it.size == 1 && it[0] == String::class.java }
            ) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) { param.result = true }
                })
                count++; log("[SYS] hooked ${m.name}(String)->true")
            }
            if (m.returnType == Void.TYPE
                && m.parameterTypes.let { it.size == 1 && it[0] == Location::class.java }
            ) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) { param.result = null }
                })
                count++; log("[SYS] hooked ${m.name}(Location)->noop")
            }
            if (m.returnType == Void.TYPE
                && m.parameterTypes.let { it.size == 1 && it[0] == List::class.java }
            ) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val orig = param.args[0] as? List<*>
                        param.args[0] = ArrayList(filterBlacklist(orig?.mapNotNull { it as? String }))
                    }
                })
                count++; log("[SYS] hooked ${m.name}(List)->dummy")
            }
        }
    } catch (e: Throwable) { log("[SYS] C2553 hook error: ${e.message}") }

    try {
        val defCls = cl.loadClass(CLS_DEFAULTS)
        for (m in defCls.declaredMethods) {
            if (Modifier.isStatic(m.modifiers)
                && m.returnType == ArrayList::class.java
                && m.parameterTypes.isEmpty()
            ) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = ArrayList<String>()
                    }
                })
                count++; log("[SYS] hooked ${m.name}()->empty [defaults]")
            }
        }
    } catch (e: Throwable) { log("[SYS] C3131 hook error: ${e.message}") }

    log("[SYS] ====== named hooks installed: $count ======")
}
