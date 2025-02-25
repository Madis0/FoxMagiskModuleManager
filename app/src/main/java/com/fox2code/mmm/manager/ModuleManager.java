package com.fox2code.mmm.manager;

import android.content.SharedPreferences;
import android.util.Log;

import com.fox2code.mmm.MainApplication;
import com.fox2code.mmm.R;
import com.fox2code.mmm.utils.Files;
import com.fox2code.mmm.utils.PropUtils;
import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.io.SuFile;
import com.topjohnwu.superuser.io.SuFileInputStream;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;

public final class ModuleManager {
    private static final String TAG = "ModuleManager";

    private static final int FLAG_MM_INVALID = ModuleInfo.FLAG_METADATA_INVALID;
    private static final int FLAG_MM_UNPROCESSED = 0x40000000;
    private static final int FLAGS_RESET_INIT = FLAG_MM_INVALID |
            ModuleInfo.FLAG_MODULE_DISABLED | ModuleInfo.FLAG_MODULE_UPDATING |
            ModuleInfo.FLAG_MODULE_UNINSTALLING | ModuleInfo.FLAG_MODULE_ACTIVE;
    private static final int FLAGS_RESET_UPDATE = FLAG_MM_INVALID | FLAG_MM_UNPROCESSED;
    private final HashMap<String, LocalModuleInfo> moduleInfos;
    private final SharedPreferences bootPrefs;
    private final Object scanLock = new Object();
    private int updatableModuleCount = 0;
    private boolean scanning;

    private static final ModuleManager INSTANCE = new ModuleManager();

    public static ModuleManager getINSTANCE() {
        return INSTANCE;
    }

    private ModuleManager() {
        this.moduleInfos = new HashMap<>();
        this.bootPrefs = MainApplication.getBootSharedPreferences();
    }

    // MultiThread friendly method
    public final void scan() {
        if (!this.scanning) {
            // Do scan
            synchronized (scanLock) {
                this.scanning = true;
                try {
                    this.scanInternal();
                } finally {
                    this.scanning = false;
                }
            }
        } else {
            // Wait for current scan
            synchronized (scanLock) {}
        }
    }

    // Pause execution until the scan is completed if one is currently running
    public final void afterScan() {
        if (this.scanning) synchronized (this.scanLock) {}
    }

    public final void runAfterScan(Runnable runnable) {
        synchronized (this.scanLock) {
            runnable.run();
        }
    }

    private void scanInternal() {
        boolean firstScan = this.bootPrefs.getBoolean("mm_first_scan", true);
        SharedPreferences.Editor editor = firstScan ? this.bootPrefs.edit() : null;
        for (ModuleInfo v : this.moduleInfos.values()) {
            v.flags |= FLAG_MM_UNPROCESSED;
            v.flags &= ~FLAGS_RESET_INIT;
            v.name = v.id;
            v.version = null;
            v.versionCode = 0;
            v.author = null;
            v.description = "";
            v.support = null;
            v.config = null;
        }
        String[] modules = new SuFile("/data/adb/modules").list();
        if (modules != null) {
            for (String module : modules) {
                if (!new SuFile("/data/adb/modules/" + module).isDirectory())
                    continue; // Ignore non directory files inside modules folder
                LocalModuleInfo moduleInfo = moduleInfos.get(module);
                if (moduleInfo == null) {
                    moduleInfo = new LocalModuleInfo(module);
                    moduleInfos.put(module, moduleInfo);
                    // Shis should not really happen, but let's handles theses cases anyway
                    moduleInfo.flags |= ModuleInfo.FLAG_MODULE_UPDATING_ONLY;
                }
                moduleInfo.flags &= ~FLAGS_RESET_UPDATE;
                boolean disabled = new SuFile(
                        "/data/adb/modules/" + module + "/disable").exists();
                if (disabled) {
                    moduleInfo.flags |= ModuleInfo.FLAG_MODULE_DISABLED;
                } else {
                    if (firstScan) {
                        moduleInfo.flags |= ModuleInfo.FLAG_MODULE_ACTIVE;
                        editor.putBoolean("module_" + moduleInfo.id + "_active", true);
                    } else if (bootPrefs.getBoolean("module_" + moduleInfo.id + "_active", false)) {
                        moduleInfo.flags |= ModuleInfo.FLAG_MODULE_ACTIVE;
                    }
                }
                boolean uninstalling = new SuFile(
                        "/data/adb/modules/" + module + "/remove").exists();
                if (uninstalling) {
                    moduleInfo.flags |= ModuleInfo.FLAG_MODULE_UNINSTALLING;
                }
                try {
                    PropUtils.readProperties(moduleInfo,
                            "/data/adb/modules/" + module + "/module.prop", true);
                } catch (Exception e) {
                    Log.d(TAG, "Failed to parse metadata!", e);
                    moduleInfo.flags |= FLAG_MM_INVALID;
                }
            }
        }
        String[] modules_update = new SuFile("/data/adb/modules_update").list();
        if (modules_update != null) {
            for (String module : modules_update) {
                LocalModuleInfo moduleInfo = moduleInfos.get(module);
                if (moduleInfo == null) {
                    moduleInfo = new LocalModuleInfo(module);
                    moduleInfos.put(module, moduleInfo);
                }
                moduleInfo.flags &= ~FLAGS_RESET_UPDATE;
                moduleInfo.flags |= ModuleInfo.FLAG_MODULE_UPDATING;
                try {
                    PropUtils.readProperties(moduleInfo,
                            "/data/adb/modules_update/" + module + "/module.prop", true);
                } catch (Exception e) {
                    Log.d(TAG, "Failed to parse metadata!", e);
                    moduleInfo.flags |= FLAG_MM_INVALID;
                }
            }
        }
        this.updatableModuleCount = 0;
        Iterator<LocalModuleInfo> moduleInfoIterator =
                this.moduleInfos.values().iterator();
        while (moduleInfoIterator.hasNext()) {
            LocalModuleInfo moduleInfo = moduleInfoIterator.next();
            if ((moduleInfo.flags & FLAG_MM_UNPROCESSED) != 0) {
                moduleInfoIterator.remove();
                continue; // Don't process fallbacks if unreferenced
            }
            if (moduleInfo.updateJson != null) {
                this.updatableModuleCount++;
            } else {
                moduleInfo.updateVersion = null;
                moduleInfo.updateVersionCode = Long.MIN_VALUE;
                moduleInfo.updateZipUrl = null;
                moduleInfo.updateChangeLog = null;
            }
            if (moduleInfo.name == null || (moduleInfo.name.equals(moduleInfo.id))) {
                moduleInfo.name = Character.toUpperCase(moduleInfo.id.charAt(0)) +
                        moduleInfo.id.substring(1).replace('_', ' ');
            }
            if (moduleInfo.version == null || moduleInfo.version.trim().isEmpty()) {
                moduleInfo.version = "v" + moduleInfo.versionCode;
            }
        }
        if (firstScan) {
            editor.putBoolean("mm_first_scan", false);
            editor.apply();
        }
    }

    public HashMap<String, LocalModuleInfo> getModules() {
        this.afterScan();
        return this.moduleInfos;
    }

    public int getUpdatableModuleCount() {
        this.afterScan();
        return this.updatableModuleCount;
    }

    public boolean setEnabledState(ModuleInfo moduleInfo, boolean checked) {
        if (moduleInfo.hasFlag(ModuleInfo.FLAG_MODULE_UPDATING) && !checked) return false;
        SuFile disable = new SuFile("/data/adb/modules/" + moduleInfo.id + "/disable");
        if (checked) {
            if (disable.exists() && !disable.delete()) {
                moduleInfo.flags |= ModuleInfo.FLAG_MODULE_DISABLED;
                return false;
            }
            moduleInfo.flags &= ~ModuleInfo.FLAG_MODULE_DISABLED;
        } else {
            if (!disable.exists() && !disable.createNewFile()) {
                return false;
            }
            moduleInfo.flags |= ModuleInfo.FLAG_MODULE_DISABLED;
        }
        return true;
    }

    public boolean setUninstallState(ModuleInfo moduleInfo, boolean checked) {
        if (checked && moduleInfo.hasFlag(ModuleInfo.FLAG_MODULE_UPDATING)) return false;
        SuFile disable = new SuFile("/data/adb/modules/" + moduleInfo.id + "/remove");
        if (checked) {
            if (!disable.exists() && !disable.createNewFile()) {
                return false;
            }
            moduleInfo.flags |= ModuleInfo.FLAG_MODULE_UNINSTALLING;
        } else {
            if (disable.exists() && !disable.delete()) {
                moduleInfo.flags |= ModuleInfo.FLAG_MODULE_UNINSTALLING;
                return false;
            }
            moduleInfo.flags &= ~ModuleInfo.FLAG_MODULE_UNINSTALLING;
        }
        return true;
    }

    public boolean masterClear(ModuleInfo moduleInfo) {
        if (moduleInfo.hasFlag(ModuleInfo.FLAG_MODULE_ACTIVE)) return false;
        String escapedId = moduleInfo.id.replace("\\", "\\\\")
                .replace("\"", "\\\"").replace(" ", "\\ ");
        try { // Check for module that declare having file outside their own folder.
            try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(
                    SuFileInputStream.open("/data/adb/modules/." + moduleInfo.id + "-files"),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    line = line.trim().replace(' ', '.');
                    if (!line.startsWith("/data/adb/") || line.contains("*") ||
                            line.contains("/../") || line.endsWith("/..") ||
                            line.startsWith("/data/adb/modules") ||
                            line.equals("/data/adb/magisk.db")) continue;
                    line = line.replace("\\", "\\\\")
                            .replace("\"", "\\\"");
                    Shell.su("rm -rf \"" + line + "\"").exec();
                }
            }
        } catch (IOException ignored) {}
        Shell.su("rm -rf /data/adb/modules/" + escapedId + "/").exec();
        Shell.su("rm -f /data/adb/modules/." + escapedId + "-files").exec();
        Shell.su("rm -rf /data/adb/modules_update/" + escapedId + "/").exec();
        moduleInfo.flags = ModuleInfo.FLAG_METADATA_INVALID;
        return true;
    }
}
