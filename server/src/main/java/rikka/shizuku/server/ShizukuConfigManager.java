package rikka.shizuku.server;

import static rikka.shizuku.server.ServerConstants.PERMISSION;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.system.ErrnoException;
import android.system.Os;
import android.util.AtomicFile;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import kotlin.collections.ArraysKt;
import af.shizuku.common.compat.Android17Compat;
import af.shizuku.common.compat.InstalledPackagesCompat;
import rikka.hidden.compat.UserManagerApis;
import rikka.shizuku.server.ktx.HandlerKt;

public class ShizukuConfigManager extends ConfigManager {

    private static final Gson GSON_IN = new GsonBuilder()
            .create();
    private static final Gson GSON_OUT = new GsonBuilder()
            .setVersion(ShizukuConfig.LATEST_VERSION)
            .create();

    /**
     * How long a batched write waits, so that start-up, which can change many entries at once,
     * writes them together. A change the user makes is written at once instead
     * ({@link #scheduleWriteLocked(boolean)}).
     */
    private static final long WRITE_DELAY = 10 * 1000;

    /** No write is waiting. */
    private static final int WRITE_NONE = 0;
    /** A write is posted to run after {@link #WRITE_DELAY}. */
    private static final int WRITE_DELAYED = 1;
    /** A write is posted to run next on the worker. */
    private static final int WRITE_PROMPT = 2;

    private static final File FILE = getConfigFile();
    private static final AtomicFile ATOMIC_FILE = new AtomicFile(FILE);

    private static File getConfigFile() {
        File shellFile = new File("/data/user_de/0/com.android.shell/shizuku.json");
        if (shellFile.exists()) {
            return shellFile;
        }
        try {
            File parent = shellFile.getParentFile();
            if (parent != null && parent.exists() && parent.canWrite()) {
                return shellFile;
            }
        } catch (Throwable ignored) {}
        return new File("/data/local/tmp/shizuku.json");
    }

    public static ShizukuConfig load() {
        FileInputStream stream;
        try {
            stream = ATOMIC_FILE.openRead();
        } catch (FileNotFoundException e) {
            LOGGER.i("no existing config file " + ATOMIC_FILE.getBaseFile() + "; starting empty");
            return new ShizukuConfig();
        }

        ShizukuConfig config = null;
        try {
            config = GSON_IN.fromJson(new InputStreamReader(stream), ShizukuConfig.class);
        } catch (Throwable tr) {
            LOGGER.e(tr, "load config");
        } finally {
            try {
                stream.close();
            } catch (IOException e) {
                LOGGER.w("failed to close: " + e);
            }
        }
        if (config != null) return config;
        return new ShizukuConfig();
    }

    /** Writes {@code json} as the config; the caller serializes it. A seam for tests. */
    static void writeJson(String json) {
        synchronized (ATOMIC_FILE) {
            FileOutputStream stream;
            try {
                stream = ATOMIC_FILE.startWrite();
            } catch (IOException e) {
                LOGGER.e("failed to write state: " + e);
                return;
            }

            try {
                stream.write(json.getBytes());

                ATOMIC_FILE.finishWrite(stream);

                // Closing #419: the config file was previously world-readable/writable.
                // Root bypasses DAC checks entirely, so it doesn't need explicit bits here;
                // only shell needs explicit access, granted via group ownership. No "other"
                // access is needed, so we restrict to owner+group read/write only.
                File file = ATOMIC_FILE.getBaseFile();
                if (file.exists()) {
                    try {
                        Os.chmod(file.getAbsolutePath(), 0660);
                        Os.chown(file.getAbsolutePath(), -1, android.os.Process.SHELL_UID);
                    } catch (ErrnoException e) {
                        LOGGER.w("failed to set permissions on " + file.getAbsolutePath() + ": " + e);
                    }
                }
                LOGGER.v("config saved to " + file.getAbsolutePath());
            } catch (Throwable tr) {
                LOGGER.e(tr, "can't save %s, restoring backup.", ATOMIC_FILE.getBaseFile());
                ATOMIC_FILE.failWrite(stream);
            }
        }
    }

    /**
     * Writes a change still waiting for the worker now, on the calling thread; does nothing when
     * none is waiting. Called before the server exits, which would otherwise lose it.
     */
    void flushPendingWrite() {
        writePending();
    }

    /**
     * Writes the config if a change is waiting. The config is serialized under this object's lock,
     * which every change holds, and written under ATOMIC_FILE's, held across both so that two
     * writes (the worker's and an exit's flush) cannot reach the disk in the other order. Nothing
     * takes ATOMIC_FILE while holding this object's lock.
     */
    private void writePending() {
        synchronized (ATOMIC_FILE) {
            String json;
            synchronized (this) {
                // Checked first: a flush with nothing waiting does not start the worker thread.
                if (pendingWrite == WRITE_NONE) {
                    return;
                }
                HandlerKt.getWorkerHandler().removeCallbacks(mWriteRunner);
                // Cleared before serializing: a change after this posts a write of its own.
                pendingWrite = WRITE_NONE;
                try {
                    json = GSON_OUT.toJson(config);
                } catch (Throwable tr) {
                    LOGGER.e(tr, "can't save %s", ATOMIC_FILE.getBaseFile());
                    return;
                }
            }
            writeJson(json);
        }
    }

    private final Runnable mWriteRunner = new Runnable() {

        @Override
        public void run() {
            writePending();
        }
    };

    private final ShizukuConfig config;

    /** WRITE_NONE, WRITE_DELAYED or WRITE_PROMPT: the write posted to the worker, if any. */
    private int pendingWrite = WRITE_NONE;

    public ShizukuConfigManager() {
        this.config = load();

        boolean changed = false;

        if (config.packages == null) {
            config.packages = new ArrayList<>();
            changed = true;
        }

        Map<Integer, List<String>> packagesByUid = new HashMap<>();
        List<PackageInfo> allPackages = new ArrayList<>();

        for (int userId : UserManagerApis.getUserIdsNoThrow()) {
            for (PackageInfo pi : InstalledPackagesCompat.getInstalledPackagesNoThrow(PackageManager.GET_PERMISSIONS, userId)) {
                if (pi == null || pi.applicationInfo == null) continue;
                allPackages.add(pi);

                List<String> list = packagesByUid.get(pi.applicationInfo.uid);
                if (list == null) {
                    list = new ArrayList<>();
                    packagesByUid.put(pi.applicationInfo.uid, list);
                }
                list.add(pi.packageName);
            }
        }

        if (packagesByUid.isEmpty()) {
            LOGGER.w("packagesByUid is empty, skipping config pruning to avoid wiping authorizations");
            return;
        }

        for (ShizukuConfig.PackageEntry entry : new ArrayList<>(config.packages)) {
            if (entry.packages == null) {
                entry.packages = new ArrayList<>();
            }

            List<String> packages = packagesByUid.get(entry.uid);
            if (packages == null || packages.isEmpty()) {
                List<String> livePackages = rikka.hidden.compat.PackageManagerApis.getPackagesForUidNoThrow(entry.uid);
                if (livePackages != null && !livePackages.isEmpty()) {
                    packages = livePackages;
                    packagesByUid.put(entry.uid, livePackages);
                } else {
                    LOGGER.i("remove config for uid %d since it has gone", entry.uid);
                    config.packages.remove(entry);
                    changed = true;
                    continue;
                }
            }

            if (entry.packages.isEmpty()) {
                // Entries created via the plain toggle path (updateFlagsForUid) used to be
                // written with no package names at all - that's missing data, not evidence this
                // uid's packages changed. Treating it as "changed" pruned a still-valid
                // authorization on every server restart, and separately made getApplications()
                // exclude the package from the authorized list on the very next refresh (its
                // membership check on this same empty list always fails). Backfill from the
                // live package list instead.
                LOGGER.i("backfilling empty packages list for uid %d from current package manager state", entry.uid);
                entry.packages.addAll(packages);
                changed = true;
                continue;
            }

            boolean packagesChanged = true;

            for (String packageName : entry.packages) {
                if (packages.contains(packageName)) {
                    packagesChanged = false;
                    break;
                }
            }

            final int rawSize = entry.packages.size();
            Set<String> s = new LinkedHashSet<>(entry.packages);
            entry.packages.clear();
            entry.packages.addAll(s);
            final int shrunkSize = entry.packages.size();
            if (shrunkSize < rawSize) {
                LOGGER.w("entry.packages has duplicate! Shrunk. (%d -> %d)", rawSize, shrunkSize);
            }

            if (packagesChanged) {
                LOGGER.i("remove config for uid %d since the packages for it changed", entry.uid);
                config.packages.remove(entry);
                changed = true;
            }
        }

        for (PackageInfo pi : allPackages) {
            if (pi.requestedPermissions == null) {
                continue;
            }

                String activePerm = null;
                if (ArraysKt.contains(pi.requestedPermissions, PERMISSION)) activePerm = PERMISSION;
                else if (ArraysKt.contains(pi.requestedPermissions, ServerConstants.PERMISSION_LEGACY)) activePerm = ServerConstants.PERMISSION_LEGACY;
                else if (ArraysKt.contains(pi.requestedPermissions, ServerConstants.PERMISSION_ORIGINAL)) activePerm = ServerConstants.PERMISSION_ORIGINAL;

                if (activePerm == null) continue;

                int uid = pi.applicationInfo.uid;
                boolean allowed;
                try {
                    allowed = Android17Compat.checkPermission(activePerm, uid) == PackageManager.PERMISSION_GRANTED;
                } catch (Throwable e) {
                    LOGGER.w("checkPermission");
                    continue;
                }

                if (allowed) {
                    List<String> packages = new ArrayList<>();
                    packages.add(pi.packageName);
                    updateLocked(uid, packages, ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED);
                    changed = true;
                }
        }

        if (changed) {
            // Start-up: one write for everything above. Under the lock, as the worker reads
            // pendingWrite under it.
            synchronized (this) {
                scheduleWriteLocked(false);
            }
        }
    }

    /**
     * Posts a write of the config to the worker: at once when {@code prompt} (a change the user
     * made, which a stop or restart soon after must not lose), otherwise after
     * {@link #WRITE_DELAY} (start-up's bulk changes, which the next start works out again). A
     * prompt write brings a delayed one forward; either one already posted covers a delayed
     * request. Each write saves the whole config, so one write covers every change before it.
     * The pending state is kept in a field rather than read from Handler.hasCallbacks, which
     * needs API 29, so it behaves the same on every API level.
     */
    private void scheduleWriteLocked(boolean prompt) {
        if (pendingWrite == WRITE_PROMPT || (pendingWrite == WRITE_DELAYED && !prompt)) {
            return;
        }
        Handler handler = HandlerKt.getWorkerHandler();
        if (prompt) {
            handler.removeCallbacks(mWriteRunner);
            handler.post(mWriteRunner);
            pendingWrite = WRITE_PROMPT;
        } else {
            handler.postDelayed(mWriteRunner, WRITE_DELAY);
            pendingWrite = WRITE_DELAYED;
        }
    }

    private ShizukuConfig.PackageEntry findLocked(int uid) {
        for (ShizukuConfig.PackageEntry entry : config.packages) {
            if (uid == entry.uid) {
                return entry;
            }
        }
        return null;
    }

    @Nullable
    public ShizukuConfig.PackageEntry find(int uid) {
        synchronized (this) {
            return findLocked(uid);
        }
    }

    public List<Integer> getAllowedUids() {
        synchronized (this) {
            List<Integer> result = new ArrayList<>();
            for (ShizukuConfig.PackageEntry entry : config.packages) {
                if ((entry.flags & ConfigManager.FLAG_ALLOWED) != 0) {
                    result.add(entry.uid);
                }
            }
            return result;
        }
    }

    public boolean isPermGrantMigrationDone() {
        synchronized (this) {
            return config.permGrantMigrationDone;
        }
    }

    public void markPermGrantMigrationDone() {
        synchronized (this) {
            if (config.permGrantMigrationDone) {
                return;
            }
            config.permGrantMigrationDone = true;
            // Written at once: if it were lost, the next start would run the one-time migration
            // again and re-grant a permission the user has since revoked (#568).
            scheduleWriteLocked(true);
        }
    }

    /** Applies the change; true when the config changed. The caller schedules the write. */
    private boolean updateLocked(int uid, List<String> packages, int mask, int values) {
        ShizukuConfig.PackageEntry entry = findLocked(uid);
        if (entry == null) {
            entry = new ShizukuConfig.PackageEntry(uid, mask & values);
            config.packages.add(entry);
        } else {
            int newValue = (entry.flags & ~mask) | (mask & values);
            if (newValue == entry.flags) {
                return false;
            }
            entry.flags = newValue;
        }
        if (packages != null) {
            for (String packageName : packages) {
                if (entry.packages.contains(packageName)) {
                    continue;
                }
                entry.packages.add(packageName);
            }
        }
        return true;
    }

    public void update(int uid, List<String> packages, int mask, int values) {
        synchronized (this) {
            if (updateLocked(uid, packages, mask, values)) {
                // The user's change: written at once, on the worker.
                scheduleWriteLocked(true);
            }
        }
    }

    private void removeLocked(int uid) {
        ShizukuConfig.PackageEntry entry = findLocked(uid);
        if (entry == null) {
            return;
        }
        config.packages.remove(entry);
        // A removal ends a grant, so it is written at once.
        scheduleWriteLocked(true);
    }

    public void remove(int uid) {
        synchronized (this) {
            removeLocked(uid);
        }
    }
}
