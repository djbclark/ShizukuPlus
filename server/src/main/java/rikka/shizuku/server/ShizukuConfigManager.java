package rikka.shizuku.server;

import static rikka.shizuku.server.ServerConstants.PERMISSION;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
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

    /**
     * How long the first retry of a failed write waits. Each further failure in a row doubles
     * it, up to {@link #WRITE_DELAY}.
     */
    private static final long RETRY_FIRST_DELAY = 1000;

    /** How many failed writes in a row between the lines that say the config is still not saved. */
    private static final int FAILURE_LOG_EVERY = 60;

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

    /**
     * Writes {@code json} as the config; the caller serializes it. A seam for tests.
     *
     * @throws IOException the config was not saved; the previous one is kept
     */
    static void writeJson(String json) throws IOException {
        synchronized (ATOMIC_FILE) {
            saveConfig(ATOMIC_FILE, json.getBytes());
            LOGGER.v("config saved to " + ATOMIC_FILE.getBaseFile().getAbsolutePath());
        }
    }

    /**
     * Writes {@code bytes} to {@code file} ({@link #writeAtomically}), then gives the file the
     * config's mode ({@link #setConfigFileMode}) whether or not the write succeeded: a write that
     * failed its checks can still leave a file it created at the base name.
     *
     * @throws IOException the bytes were not saved
     */
    static void saveConfig(AtomicFile file, byte[] bytes) throws IOException {
        try {
            writeAtomically(file, bytes);
        } finally {
            setConfigFileMode(file.getBaseFile());
        }
    }

    /**
     * Mode 0660, group shell, on {@code file} if it exists. A failure is logged; it never counts
     * as a failed save.
     */
    static void setConfigFileMode(File file) {
        // Closing #419: the config file was previously world-readable/writable.
        // Root bypasses DAC checks entirely, so it doesn't need explicit bits here;
        // only shell needs explicit access, granted via group ownership. No "other"
        // access is needed, so we restrict to owner+group read/write only.
        if (!file.exists()) {
            return;
        }
        try {
            Os.chmod(file.getAbsolutePath(), 0660);
            Os.chown(file.getAbsolutePath(), -1, android.os.Process.SHELL_UID);
        } catch (ErrnoException | RuntimeException e) {
            LOGGER.w("failed to set permissions on " + file.getAbsolutePath() + ": " + e);
        }
    }

    /** {@link Build.VERSION#SDK_INT}; a seam for tests. */
    static int sdkInt() {
        return Build.VERSION.SDK_INT;
    }

    /**
     * Writes {@code bytes} to {@code file} and checks that they reached it.
     * {@link AtomicFile#finishWrite} does not report a failed sync, close or (on API 30+) rename
     * of the new file into place: it only logs it. So the data is synced here before
     * finishWrite, and afterwards the base file must hold as many bytes as were written, with no
     * backup left beside it (AtomicFile reads a backup in place of the base file) and, on API
     * 30+, no new file (one left was not renamed into place).
     *
     * @throws IOException the bytes were not saved; AtomicFile keeps the previous file
     */
    static void writeAtomically(AtomicFile file, byte[] bytes) throws IOException {
        synchronized (file) {
            FileOutputStream stream = file.startWrite();
            try {
                stream.write(bytes);
                stream.getFD().sync();
            } catch (Throwable tr) {
                file.failWrite(stream);
                throw tr;
            }
            file.finishWrite(stream);

            File base = file.getBaseFile();
            File newFile = new File(base.getPath() + ".new");
            File backup = new File(base.getPath() + ".bak");
            String problem = null;
            // Only API 30+ writes through a new file; before it, one there is not this write's.
            if (sdkInt() >= 30 && newFile.exists()) {
                problem = "the new file was not renamed into place";
                // Only ever the file this write made; the next write would truncate it anyway.
                if (!newFile.delete()) {
                    LOGGER.w("cannot delete " + newFile);
                }
            } else if (backup.exists()) {
                problem = "the backup is still in place";
            } else if (base.length() != bytes.length) {
                problem = "it holds " + base.length() + " bytes of " + bytes.length;
            }
            if (problem != null) {
                throw new IOException("can't save " + base + ": " + problem);
            }
        }
    }

    /**
     * Writes a change still waiting for the worker now, on the calling thread; does nothing when
     * none is waiting. Called before the server exits, which would otherwise lose it.
     *
     * @return false when a change is still waiting afterwards: the write failed, or a change was
     * made after it
     */
    boolean flushPendingWrite() {
        writePending();
        synchronized (this) {
            return pendingWrite == WRITE_NONE;
        }
    }

    /**
     * Writes the config if a change is waiting. The config is serialized under this object's lock,
     * which every change holds, and written under ATOMIC_FILE's, held across both so that two
     * writes (the worker's and an exit's flush) cannot reach the disk in the other order. Nothing
     * takes ATOMIC_FILE while holding this object's lock.
     *
     * <p>A write that fails leaves the change waiting and posts a retry
     * ({@link #writeFailedLocked(Throwable)}), so the worker tries again and an exit's flush finds
     * it waiting.
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
                    writeFailedLocked(tr);
                    return;
                }
            }
            Throwable failure = null;
            try {
                writeJson(json);
            } catch (Throwable tr) {
                failure = tr;
            }
            synchronized (this) {
                if (failure != null) {
                    writeFailedLocked(failure);
                } else if (writeFailures > 0) {
                    LOGGER.i("config saved to %s after %d failed attempts", ATOMIC_FILE.getBaseFile(), writeFailures);
                    writeFailures = 0;
                }
            }
        }
    }

    /**
     * After a write that failed: the change waits again, and a retry is posted after
     * {@link #retryDelay(int)}. A prompt write posted meanwhile (a change made during the write)
     * is left to run as posted, and a delayed one is brought forward to the retry. The failure is
     * logged with its cause at the start of a run of failures, again in one line every
     * {@link #FAILURE_LOG_EVERY} failures while the run lasts, and the write that ends the run
     * says so.
     */
    private void writeFailedLocked(Throwable failure) {
        writeFailures++;
        if (writeFailures % FAILURE_LOG_EVERY == 0) {
            LOGGER.w("%s still not saved after %d failed attempts: %s", ATOMIC_FILE.getBaseFile(), writeFailures, failure);
        }
        if (pendingWrite == WRITE_PROMPT) {
            if (writeFailures == 1) {
                LOGGER.e(failure, "can't save %s; the previous config is kept, trying again now",
                        ATOMIC_FILE.getBaseFile());
            }
            return;
        }
        long delay = retryDelay(writeFailures);
        if (writeFailures == 1) {
            LOGGER.e(failure, "can't save %s; the previous config is kept, trying again in %d ms",
                    ATOMIC_FILE.getBaseFile(), delay);
        }
        Handler handler = HandlerKt.getWorkerHandler();
        handler.removeCallbacks(mWriteRunner);
        handler.postDelayed(mWriteRunner, delay);
        pendingWrite = WRITE_DELAYED;
    }

    /** {@link #RETRY_FIRST_DELAY}, doubled for each failure in a row after the first, at most {@link #WRITE_DELAY}. */
    private static long retryDelay(int failures) {
        int doublings = Math.min(Math.max(failures - 1, 0), 16);
        return Math.min(WRITE_DELAY, RETRY_FIRST_DELAY << doublings);
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

    /** Writes that failed in a row; 0 after one that succeeded. */
    private int writeFailures = 0;

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
