package rikka.shizuku.server

import af.shizuku.common.compat.Android17Compat
import af.shizuku.common.compat.InstalledPackagesCompat
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.system.Os
import android.util.AtomicFile
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.UserManagerApis
import rikka.shizuku.server.ktx.workerHandler
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A grant, revoke or removal the user makes must reach the config file promptly: the server can
 * be stopped (the app's Stop) or killed and started again (a restart from the app) at any moment,
 * and a change still waiting to be written is then lost. Start-up, which can change many entries
 * at once, still writes once after a delay. The worker handler here is a fake with its own clock:
 * [advance] runs what is due, and every config write is recorded instead of reaching the disk.
 */
class ConfigWriteTimingTest {

    private val stored = ShizukuConfig()
    private var installed: List<PackageInfo> = emptyList()
    private val writes = Collections.synchronizedList(mutableListOf<ShizukuConfig>())
    private var duringWrite: (() -> Unit)? = null
    /** How many of the next writes fail, as a full disk would fail them. */
    private var failWrites = 0
    /** The clock at every write attempt, failed or not. */
    private val attempts = mutableListOf<Long>()

    @TempDir
    lateinit var dir: Path

    private var now = 0L
    private val queue = mutableListOf<Posted>()

    private class Posted(val runnable: Runnable, val at: Long)

    @BeforeEach
    fun setup() {
        mockkStatic(
            ShizukuConfigManager::class, InstalledPackagesCompat::class, UserManagerApis::class,
            PackageManagerApis::class, Android17Compat::class,
        )
        val handler = mockk<Handler>()
        every { handler.post(any()) } answers { queue.add(Posted(firstArg(), now)) }
        every { handler.postDelayed(any<Runnable>(), any<Long>()) } answers {
            queue.add(Posted(firstArg(), now + secondArg<Long>()))
        }
        every { handler.removeCallbacks(any<Runnable>()) } answers {
            val runnable = firstArg<Runnable>()
            queue.removeAll { it.runnable === runnable }
            Unit
        }
        mockkStatic("rikka.shizuku.server.ktx.HandlerKt")
        every { workerHandler } returns handler

        every { ShizukuConfigManager.load() } returns stored
        every { ShizukuConfigManager.sdkInt() } returns 30
        every { ShizukuConfigManager.writeJson(any()) } answers {
            attempts.add(now)
            if (failWrites > 0) {
                failWrites--
                duringWrite?.let {
                    duringWrite = null
                    it()
                }
                throw IOException("No space left on device")
            }
            writes.add(Gson().fromJson(firstArg<String>(), ShizukuConfig::class.java))
            duringWrite?.let {
                duringWrite = null
                it()
            }
        }
        every { UserManagerApis.getUserIdsNoThrow() } returns listOf(0)
        every { InstalledPackagesCompat.getInstalledPackagesNoThrow(any(), 0) } answers { installed }
        every { PackageManagerApis.getPackagesForUidNoThrow(any()) } answers { listOf(packageOf(firstArg())) }
        every { Android17Compat.checkPermission(any<String>(), any<Int>()) } returns PackageManager.PERMISSION_DENIED

        // Start-up skips its reconcile when no package is installed; give it the apps under test.
        installed = listOf(holder(APP_UID, null), holder(OTHER_UID, null))
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    // --- the user's changes --------------------------------------------------------------------

    @Test
    fun `a grant made after start-up is written without waiting for the batch delay`() {
        val manager = start()

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED)
        advance(0)

        assertEquals(1, writes.size, "the grant was not written at once")
        assertEquals(ConfigManager.FLAG_ALLOWED, flagsOf(writes.last(), APP_UID))
    }

    @Test
    fun `a revoke made after start-up is written without waiting for the batch delay`() {
        storeGrant(APP_UID)
        val manager = start()
        advance(BATCH_DELAY)
        writes.clear()

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        advance(0)

        assertEquals(1, writes.size, "the revoke was not written at once")
        assertEquals(ConfigManager.FLAG_DENIED, flagsOf(writes.last(), APP_UID))
    }

    @Test
    fun `a removal made after start-up is written without waiting for the batch delay`() {
        storeGrant(APP_UID)
        val manager = start()
        advance(BATCH_DELAY)
        writes.clear()

        manager.remove(APP_UID)
        advance(0)

        assertEquals(1, writes.size, "the removal was not written at once")
        assertEquals(null, flagsOf(writes.last(), APP_UID))
    }

    @Test
    fun `a change that changes nothing is not written`() {
        storeGrant(APP_UID)
        val manager = start()
        advance(BATCH_DELAY)
        writes.clear()

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED)
        advance(BATCH_DELAY)

        assertTrue(writes.isEmpty())
    }

    @Test
    fun `changes made together are written once`() {
        val manager = start()

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED)
        manager.update(OTHER_UID, listOf(packageOf(OTHER_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        advance(0)
        advance(BATCH_DELAY)

        assertEquals(1, writes.size)
        assertEquals(ConfigManager.FLAG_ALLOWED, flagsOf(writes.single(), APP_UID))
        assertEquals(ConfigManager.FLAG_DENIED, flagsOf(writes.single(), OTHER_UID))
    }

    @Test
    fun `a change made while a write runs is written by a write of its own`() {
        val manager = start()
        duringWrite = {
            manager.update(OTHER_UID, listOf(packageOf(OTHER_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        }

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED)
        advance(0)

        assertEquals(2, writes.size, "the change made during the first write was left for later")
        assertEquals(ConfigManager.FLAG_DENIED, flagsOf(writes.last(), OTHER_UID))
        assertEquals(ConfigManager.FLAG_ALLOWED, flagsOf(writes.last(), APP_UID))
    }

    @Test
    fun `marking the permission migration done is written at once`() {
        val manager = start()

        manager.markPermGrantMigrationDone()
        advance(0)

        assertEquals(1, writes.size, "the migration flag was not written at once")
        assertTrue(writes.single().permGrantMigrationDone)
    }

    // --- start-up ------------------------------------------------------------------------------

    @Test
    fun `start-up's changes are written once, after the batch delay`() {
        importHolders(10_201, 10_202, 10_203)

        start()
        advance(BATCH_DELAY - 1)
        assertTrue(writes.isEmpty(), "start-up's changes were written before the batch delay")
        advance(1)

        assertEquals(1, writes.size)
        assertEquals(3, writes.single().packages.size)
        advance(BATCH_DELAY)
        assertEquals(1, writes.size)
    }

    @Test
    fun `start-up keeps its batched write exactly as on master when an app already holds the permission`() {
        storeGrant(10_201)
        importHolders(10_201)

        start()
        advance(BATCH_DELAY - 1)
        assertTrue(writes.isEmpty(), "start-up's write ran before the batch delay")
        advance(1)

        assertEquals(1, writes.size, "start-up did not keep its batched write")
        assertEquals(ConfigManager.FLAG_ALLOWED, flagsOf(writes.single(), 10_201))
        advance(BATCH_DELAY)
        assertEquals(1, writes.size)
    }

    @Test
    fun `a grant while start-up's write waits brings it forward, and one write holds both`() {
        importHolders(10_201, 10_202)
        val manager = start()

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED)
        advance(0)

        assertEquals(1, writes.size, "the grant waited behind start-up's write")
        val written = writes.single()
        assertEquals(ConfigManager.FLAG_ALLOWED, flagsOf(written, APP_UID))
        assertEquals(ConfigManager.FLAG_ALLOWED, flagsOf(written, 10_201))
        assertEquals(ConfigManager.FLAG_ALLOWED, flagsOf(written, 10_202))
        advance(BATCH_DELAY)
        assertEquals(1, writes.size, "start-up's write ran again with nothing new")
    }

    // --- flush ---------------------------------------------------------------------------------

    @Test
    fun `a flush writes a waiting change on the calling thread and the worker does not write it again`() {
        importHolders(10_201)
        val manager = start()
        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)

        manager.flushPendingWrite()

        assertEquals(1, writes.size, "the flush did not write the waiting change")
        assertEquals(ConfigManager.FLAG_DENIED, flagsOf(writes.single(), APP_UID))
        assertEquals(ConfigManager.FLAG_ALLOWED, flagsOf(writes.single(), 10_201))
        advance(BATCH_DELAY)
        assertEquals(1, writes.size)
    }

    @Test
    fun `a flush with nothing waiting writes nothing and does not start the worker`() {
        val manager = start()

        manager.flushPendingWrite()
        advance(BATCH_DELAY)

        assertTrue(writes.isEmpty())
        verify(exactly = 0) { workerHandler }
    }

    @Test
    fun `the worker's write and an exit's flush on another thread are written in order`() {
        // The worker's write of change A is held after the manager serialized the config and before
        // the file is written, which is where a flush that did not wait for it would overtake it
        // with a newer copy and then be overwritten by the older one.
        val onDisk = Collections.synchronizedList(mutableListOf<ShizukuConfig>())
        val workerWriting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        every { ShizukuConfigManager.writeJson(any()) } answers {
            if (first.getAndSet(false)) {
                workerWriting.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            onDisk.add(Gson().fromJson(firstArg<String>(), ShizukuConfig::class.java))
        }
        val manager = start()

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED)
        val write = queue.single().runnable
        queue.clear()
        val worker = thread(name = "Worker") { write.run() }
        assertTrue(workerWriting.await(5, TimeUnit.SECONDS), "the worker did not start writing")

        manager.update(OTHER_UID, listOf(packageOf(OTHER_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        val flushed = CountDownLatch(1)
        val exiting = thread(name = "exit") {
            manager.flushPendingWrite()
            flushed.countDown()
        }
        assertFalse(flushed.await(200, TimeUnit.MILLISECONDS), "the flush did not wait for the write in progress")
        release.countDown()
        worker.join(5_000)
        exiting.join(5_000)

        assertEquals(2, onDisk.size, "a write was lost or written twice")
        assertEquals(ConfigManager.FLAG_ALLOWED, flagsOf(onDisk[0], APP_UID))
        assertEquals(null, flagsOf(onDisk[0], OTHER_UID), "the worker's write held a change made after it serialized")
        assertEquals(ConfigManager.FLAG_ALLOWED, flagsOf(onDisk[1], APP_UID))
        assertEquals(ConfigManager.FLAG_DENIED, flagsOf(onDisk[1], OTHER_UID), "the flush lost the change made during the write")
        assertTrue(queue.isEmpty(), "the worker still has a write waiting after the flush")
    }

    // --- failed writes -------------------------------------------------------------------------

    @Test
    fun `a write that fails is tried again and the change reaches the disk`() {
        // The real write path over a real directory, with AtomicFile's rename of the new file into
        // place failing on the first attempt: AtomicFile only logs that, so the write must notice.
        val file = FakeAtomicFile(dir.resolve("shizuku.json").toFile()).apply { renameFails = 1 }
        every { ShizukuConfigManager.writeJson(any()) } answers {
            attempts.add(now)
            ShizukuConfigManager.saveConfig(file.mock, firstArg<String>().toByteArray())
        }
        val manager = start()

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        advance(0)
        assertFalse(file.base.exists(), "the failed write left something at the config's name")
        assertEquals(emptyList(), dir.toFile().list()!!.toList(), "the failed write left its new file")
        advance(BATCH_DELAY)

        assertEquals(2, attempts.size, "the failed write was not tried again, or was tried more than once")
        val onDisk = Gson().fromJson(file.base.readText(), ShizukuConfig::class.java)
        assertEquals(ConfigManager.FLAG_DENIED, flagsOf(onDisk, APP_UID))
        assertEquals(listOf("shizuku.json"), dir.toFile().list()!!.sorted(), "a new file was left behind")
        assertTrue(queue.isEmpty(), "a write is still waiting after the change was saved")
    }

    @Test
    fun `before API 30 a backup left by a failed write is cleared by the retry that saves the change`() {
        // AtomicFile before API 30: the old config becomes the backup, the base is written in
        // place, and a close that fails is only logged and keeps the backup, which is then read
        // in place of the base.
        every { ShizukuConfigManager.sdkInt() } returns 29
        val file = FakeAtomicFile(dir.resolve("shizuku.json").toFile(), pre30 = true).apply { closeFails = 1 }
        file.base.writeText("{}")
        every { ShizukuConfigManager.writeJson(any()) } answers {
            attempts.add(now)
            ShizukuConfigManager.saveConfig(file.mock, firstArg<String>().toByteArray())
        }
        val manager = start()

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        advance(0)
        assertEquals(1, attempts.size)
        assertEquals("{}", file.backup.readText(), "the failed write did not keep the previous config as the backup")
        advance(BATCH_DELAY)

        assertEquals(2, attempts.size, "the failed write was not tried again, or was tried more than once")
        assertEquals(listOf("shizuku.json"), dir.toFile().list()!!.sorted(), "the retry left the backup")
        val onDisk = Gson().fromJson(file.base.readText(), ShizukuConfig::class.java)
        assertEquals(ConfigManager.FLAG_DENIED, flagsOf(onDisk, APP_UID))
        assertTrue(queue.isEmpty(), "a write is still waiting after the change was saved")
    }

    @Test
    fun `an exit flush after a failed write writes the change`() {
        val manager = start()
        failWrites = 1

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        advance(0)
        assertTrue(writes.isEmpty())
        assertTrue(manager.flushPendingWrite(), "a flush that wrote the change reported it as not saved")

        assertEquals(1, writes.size, "the exit flush found nothing waiting after the failed write")
        assertEquals(ConfigManager.FLAG_DENIED, flagsOf(writes.single(), APP_UID))
        advance(BATCH_DELAY)
        assertEquals(1, writes.size, "the retry wrote again after the flush had saved the change")
    }

    @Test
    fun `an exit flush that fails says the change is not saved and leaves one retry waiting`() {
        val manager = start()
        failWrites = 2

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        advance(0)

        assertFalse(manager.flushPendingWrite(), "a failed flush reported the change as saved")
        assertEquals(2, attempts.size)
        assertEquals(1, queue.size, "not exactly one retry waiting after the failed flush")
        assertTrue(manager.flushPendingWrite(), "a flush that wrote the change reported it as not saved")
        assertEquals(ConfigManager.FLAG_DENIED, flagsOf(writes.single(), APP_UID))
        assertTrue(manager.flushPendingWrite(), "a flush with nothing waiting reported a change not saved")
    }

    @Test
    fun `a change made while a write fails is written at once and the earlier change with it`() {
        val manager = start()
        failWrites = 1
        duringWrite = {
            manager.update(OTHER_UID, listOf(packageOf(OTHER_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        }

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED)
        advance(0)

        assertEquals(listOf(0L, 0L), attempts, "the change made during the failed write waited for the retry")
        assertEquals(1, writes.size)
        assertEquals(ConfigManager.FLAG_ALLOWED, flagsOf(writes.single(), APP_UID))
        assertEquals(ConfigManager.FLAG_DENIED, flagsOf(writes.single(), OTHER_UID))
        advance(BATCH_DELAY)
        assertEquals(1, writes.size, "a write ran again with nothing new")
    }

    @Test
    fun `changes made while writes keep failing are all written once a write succeeds`() {
        val manager = start()
        failWrites = 3

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        advance(0)
        manager.update(OTHER_UID, listOf(packageOf(OTHER_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED)
        advance(0)
        manager.update(THIRD_UID, listOf(packageOf(THIRD_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        advance(BATCH_DELAY * 3)

        assertEquals(4, attempts.size)
        assertEquals(1, writes.size)
        assertEquals(ConfigManager.FLAG_DENIED, flagsOf(writes.single(), APP_UID))
        assertEquals(ConfigManager.FLAG_ALLOWED, flagsOf(writes.single(), OTHER_UID))
        assertEquals(ConfigManager.FLAG_DENIED, flagsOf(writes.single(), THIRD_UID))
        assertTrue(queue.isEmpty(), "a write is still waiting after every change was saved")
    }

    @Test
    fun `start-up's delayed write that fails is tried again`() {
        importHolders(10_201)
        failWrites = 1

        start()
        advance(BATCH_DELAY)
        assertTrue(writes.isEmpty())
        advance(FIRST_RETRY)

        assertEquals(listOf(BATCH_DELAY, BATCH_DELAY + FIRST_RETRY), attempts)
        assertEquals(ConfigManager.FLAG_ALLOWED, flagsOf(writes.single(), 10_201))
    }

    @Test
    fun `writes that keep failing are tried again at a bounded rate`() {
        val manager = start()
        failWrites = Int.MAX_VALUE

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        advance(MINUTE)

        val gaps = attempts.zipWithNext { a, b -> b - a }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L), gaps.take(5), "the retries did not back off")
        assertTrue(attempts.size <= 12, "the failed write was tried ${attempts.size} times in a minute: $attempts")
        assertTrue(gaps.all { it in 1_000..BATCH_DELAY }, "attempts closer than a second or further than the batch delay apart: $attempts")
        assertEquals(1, queue.size, "not exactly one retry waiting")
    }

    @Test
    fun `a write that succeeds ends the failure streak`() {
        val manager = start()
        failWrites = 4

        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        advance(MINUTE)
        assertEquals(1, writes.size)
        attempts.clear()
        failWrites = 1

        manager.update(OTHER_UID, listOf(packageOf(OTHER_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
        val first = now
        advance(MINUTE)

        assertEquals(2, attempts.size)
        assertEquals(first, attempts[0])
        assertEquals(FIRST_RETRY, attempts[1] - attempts[0], "the retry kept the earlier streak's longer wait")
        assertEquals(2, writes.size)
    }

    // --- the AtomicFile write ------------------------------------------------------------------

    @Test
    fun `an AtomicFile write that completes saves the bytes and leaves nothing beside them`() {
        val file = FakeAtomicFile(dir.resolve("shizuku.json").toFile())

        ShizukuConfigManager.writeAtomically(file.mock, "{\"version\":1}".toByteArray())

        assertEquals("{\"version\":1}", file.base.readText())
        assertEquals(listOf("shizuku.json"), dir.toFile().list()!!.sorted())
    }

    @Test
    fun `an AtomicFile rename that fails is reported and the previous config is kept`() {
        // AtomicFile.finishWrite on API 30+ only logs a failed rename of the new file into place.
        val file = FakeAtomicFile(dir.resolve("shizuku.json").toFile()).apply { renameFails = 1 }
        file.base.writeText("old")

        assertThrows<IOException> { ShizukuConfigManager.writeAtomically(file.mock, "new config".toByteArray()) }

        assertEquals("old", file.base.readText())
        assertEquals(listOf("shizuku.json"), dir.toFile().list()!!.sorted(), "the new file was left behind")
    }

    @Test
    fun `an AtomicFile backup left in place before API 30 is reported`() {
        // Before API 30, finishWrite only logs a failed close and then keeps the backup, which
        // AtomicFile reads in place of the base file.
        every { ShizukuConfigManager.sdkInt() } returns 29
        val file = FakeAtomicFile(dir.resolve("shizuku.json").toFile(), pre30 = true).apply { closeFails = 1 }
        file.base.writeText("old")

        assertThrows<IOException> { ShizukuConfigManager.writeAtomically(file.mock, "new config".toByteArray()) }

        assertEquals("old", file.backup.readText())
    }

    @Test
    fun `before API 30 a new file the write does not use does not fail it`() {
        every { ShizukuConfigManager.sdkInt() } returns 29
        val file = FakeAtomicFile(dir.resolve("shizuku.json").toFile(), pre30 = true)
        File(file.newFile, "x").apply { parentFile.mkdirs() }.writeText("")

        ShizukuConfigManager.writeAtomically(file.mock, "new config".toByteArray())

        assertEquals("new config", file.base.readText())
    }

    @Test
    fun `a saved config gets mode 0660 and group shell`() {
        mockkStatic(Os::class)
        val file = FakeAtomicFile(dir.resolve("shizuku.json").toFile())

        ShizukuConfigManager.saveConfig(file.mock, "new config".toByteArray())

        verify { Os.chmod(file.base.absolutePath, MODE_0660) }
        verify { Os.chown(file.base.absolutePath, -1, SHELL_UID) }
    }

    @Test
    fun `a config left by a write that failed its check still gets mode 0660 and group shell`() {
        // Before API 30 the base is a file the write created even when the check after
        // finishWrite fails, so it must not keep the mode it was created with.
        every { ShizukuConfigManager.sdkInt() } returns 29
        mockkStatic(Os::class)
        val file = FakeAtomicFile(dir.resolve("shizuku.json").toFile(), pre30 = true).apply { closeFails = 1 }
        file.base.writeText("old")

        assertThrows<IOException> { ShizukuConfigManager.saveConfig(file.mock, "new config".toByteArray()) }

        verify { Os.chmod(file.base.absolutePath, MODE_0660) }
        verify { Os.chown(file.base.absolutePath, -1, SHELL_UID) }
    }

    @Test
    fun `a mode change that fails does not fail a saved config`() {
        mockkStatic(Os::class)
        every { Os.chmod(any(), any()) } throws RuntimeException("chmod")
        val file = FakeAtomicFile(dir.resolve("shizuku.json").toFile())

        ShizukuConfigManager.saveConfig(file.mock, "new config".toByteArray())

        assertEquals("new config", file.base.readText())
    }

    @Test
    fun `an AtomicFile write that fails before finishing is reported and abandoned`() {
        val file = FakeAtomicFile(dir.resolve("shizuku.json").toFile()).apply { writeFails = true }

        assertThrows<IOException> { ShizukuConfigManager.writeAtomically(file.mock, "new config".toByteArray()) }

        assertEquals(1, file.failed, "the write was not abandoned through failWrite")
        assertEquals(0, file.finished, "a write that failed was finished")
        assertEquals(emptyList(), dir.toFile().list()!!.toList())
    }

    @Test
    fun `an AtomicFile that cannot start a write is reported`() {
        val file = FakeAtomicFile(dir.resolve("shizuku.json").toFile())
        every { file.mock.startWrite() } throws IOException("No space left on device")

        assertThrows<IOException> { ShizukuConfigManager.writeAtomically(file.mock, "new config".toByteArray()) }
    }

    // --- exit ----------------------------------------------------------------------------------

    @Test
    fun `the app's Stop writes a waiting change before the server exits`() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val manager = mockk<ShizukuConfigManager>()
        every { manager.flushPendingWrite() } answers {
            order.add("flush")
            true
        }
        val service = serviceExitingInto(order)
        setConfigManager(service, manager)

        service.exit()

        assertEquals(listOf("flush", "exit 0"), order)
    }

    @Test
    fun `an exit still ends the server when the config cannot be written`() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val manager = mockk<ShizukuConfigManager>()
        every { manager.flushPendingWrite() } throws IllegalStateException("cannot write")
        val service = serviceExitingInto(order)
        setConfigManager(service, manager)

        service.exit()

        assertEquals(listOf("exit 0"), order)
    }

    @Test
    fun `an exit still ends the server when the flush reports the change not saved`() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val manager = mockk<ShizukuConfigManager>()
        every { manager.flushPendingWrite() } answers {
            order.add("flush")
            false
        }
        val service = serviceExitingInto(order)
        setConfigManager(service, manager)

        service.exit()

        assertEquals(listOf("flush", "exit 0"), order)
    }

    @Test
    fun `an exit does not wait longer than its bound for a config write that does not finish`() {
        // The worker's write hangs while it holds the config file, as a stalled disk would.
        val stuck = CountDownLatch(1)
        val hung = CountDownLatch(1)
        val written = CountDownLatch(2)
        every { ShizukuConfigManager.writeJson(any()) } answers {
            hung.countDown()
            stuck.await(10, TimeUnit.SECONDS)
            written.countDown()
            Unit
        }
        val manager = start()
        manager.update(APP_UID, listOf(packageOf(APP_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED)
        val write = queue.single().runnable
        queue.clear()
        val worker = thread(name = "Worker") { write.run() }
        try {
            assertTrue(hung.await(5, TimeUnit.SECONDS))
            manager.update(OTHER_UID, listOf(packageOf(OTHER_UID)), ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_DENIED)
            val order = Collections.synchronizedList(mutableListOf<String>())
            val service = serviceExitingInto(order)
            every { ShizukuService.exitFlushTimeoutMillis() } returns EXIT_BOUND
            setConfigManager(service, manager)

            val started = System.nanoTime()
            service.exit()
            val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            assertEquals(listOf("exit 0"), order)
            assertTrue(waited >= EXIT_BOUND, "the exit did not wait for the write at all ($waited ms)")
            assertTrue(waited < 5_000, "the exit waited $waited ms for a write that does not finish")
        } finally {
            // The flush left behind writes once the worker's write ends; let it, before teardown.
            stuck.countDown()
            worker.join(5_000)
            written.await(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `the uncaught-exception handler writes a waiting change and exits with status 1`() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val manager = mockk<ShizukuConfigManager>()
        every { manager.flushPendingWrite() } answers {
            order.add("flush")
            true
        }
        val service = serviceExitingInto(order)
        setConfigManager(service, manager)

        service.onUncaughtException(Thread.currentThread(), IllegalStateException("crash"))

        assertEquals(listOf("flush", "exit 1"), order)
    }

    @Test
    fun `the uncaught-exception handler exits within its bound when the config write hangs`() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val stuck = CountDownLatch(1)
        val manager = mockk<ShizukuConfigManager>()
        every { manager.flushPendingWrite() } answers {
            stuck.await(10, TimeUnit.SECONDS)
            true
        }
        val service = serviceExitingInto(order)
        every { ShizukuService.exitFlushTimeoutMillis() } returns EXIT_BOUND
        setConfigManager(service, manager)

        try {
            service.onUncaughtException(Thread.currentThread(), IllegalStateException("crash"))
            assertEquals(listOf("exit 1"), order)
        } finally {
            stuck.countDown()
        }
    }

    @Test
    fun `an exit before the config manager is stored does not write`() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val service = serviceExitingInto(order)

        service.onUncaughtException(Thread.currentThread(), IllegalStateException("crash"))

        assertEquals(listOf("exit 1"), order)
    }

    /** A ShizukuService whose exits are recorded in [order] instead of ending the JVM. */
    private fun serviceExitingInto(order: MutableList<String>): ShizukuService {
        // enforceManagerPermission lets a call from the server's own pid through.
        mockkStatic(Binder::class, Os::class)
        every { Binder.getCallingUid() } returns 0
        every { Binder.getCallingPid() } returns SERVER_PID
        every { Os.getpid() } returns SERVER_PID
        mockkStatic(ShizukuService::class)
        every { ShizukuService.exitProcess(any()) } answers {
            order.add("exit ${firstArg<Int>()}")
            Unit
        }
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        // The constructor starts the real server; the exit paths need only the config manager.
        return unsafe.allocateInstance(ShizukuService::class.java) as ShizukuService
    }

    private fun setConfigManager(service: ShizukuService, manager: ShizukuConfigManager) {
        val field = ShizukuService::class.java.getDeclaredField("configManager")
        field.isAccessible = true
        field.set(service, manager)
    }

    // --- helpers -------------------------------------------------------------------------------

    /**
     * An AtomicFile (a stub in unit tests) over a real file that behaves as the framework's does.
     * On API 30+ a write goes to a ".new" file that finishWrite renames into place; before API 30
     * startWrite moves the base to ".bak", the base is written in place, finishWrite deletes the
     * backup and failWrite puts it back. A failed rename or close is only logged by the
     * framework, so here it is silent.
     */
    private class FakeAtomicFile(val base: File, val pre30: Boolean = false) {
        val newFile = File(base.path + ".new")
        val backup = File(base.path + ".bak")
        /** How many of the next finishWrite calls do not rename the new file into place (API 30+). */
        var renameFails = 0
        /** How many of the next finishWrite calls fail to close, which keeps the backup (before API 30). */
        var closeFails = 0
        /** The stream throws on write, as a full disk does. */
        var writeFails = false
        var finished = 0
        var failed = 0

        val mock = mockk<AtomicFile>().also { file ->
            every { file.baseFile } returns base
            every { file.startWrite() } answers {
                val target = if (pre30) {
                    if (base.exists()) {
                        if (!backup.exists()) base.renameTo(backup) else base.delete()
                    }
                    base
                } else {
                    newFile
                }
                if (writeFails) {
                    object : FileOutputStream(target) {
                        override fun write(b: ByteArray) = throw IOException("No space left on device")
                    }
                } else {
                    FileOutputStream(target)
                }
            }
            every { file.finishWrite(any()) } answers {
                finished++
                firstArg<FileOutputStream>().close()
                if (pre30) {
                    if (closeFails > 0) closeFails-- else backup.delete()
                } else {
                    if (renameFails > 0) renameFails-- else newFile.renameTo(base)
                }
                Unit
            }
            every { file.failWrite(any()) } answers {
                failed++
                firstArg<FileOutputStream>().close()
                if (pre30) {
                    base.delete()
                    backup.renameTo(base)
                } else {
                    newFile.delete()
                }
                Unit
            }
        }
    }

    private fun start() = ShizukuConfigManager()

    /** Runs every posted runnable due within [ms] from now, in order, and moves the clock on. */
    private fun advance(ms: Long) {
        val until = now + ms
        while (true) {
            val next = queue.filter { it.at <= until }.minByOrNull { it.at } ?: break
            queue.remove(next)
            now = maxOf(now, next.at)
            next.runnable.run()
        }
        now = until
    }

    private fun storeGrant(uid: Int) {
        stored.packages.add(
            ShizukuConfig.PackageEntry(uid, ConfigManager.FLAG_ALLOWED).apply {
                packages.add(packageOf(uid))
            }
        )
    }

    /** Installed packages that hold the API permission, which start-up records as granted. */
    private fun importHolders(vararg uids: Int) {
        installed = installed + uids.map { holder(it, ServerConstants.PERMISSION) }
        for (uid in uids) {
            every { Android17Compat.checkPermission(ServerConstants.PERMISSION, uid) } returns PackageManager.PERMISSION_GRANTED
        }
    }

    private fun holder(uid: Int, permission: String?) = PackageInfo().apply {
        packageName = packageOf(uid)
        applicationInfo = ApplicationInfo().apply { this.uid = uid }
        requestedPermissions = permission?.let { arrayOf(it) }
    }

    private fun packageOf(uid: Int) = "com.example.app$uid"

    private fun flagsOf(config: ShizukuConfig, uid: Int): Int? =
        config.packages.firstOrNull { it.uid == uid }?.flags?.and(ConfigManager.MASK_PERMISSION)

    private companion object {
        const val APP_UID = 10_123
        const val OTHER_UID = 10_124
        const val THIRD_UID = 10_125
        const val MINUTE = 60_000L
        const val FIRST_RETRY = 1_000L
        const val MODE_0660 = 0b110_110_000
        const val SHELL_UID = 2000
        const val BATCH_DELAY = 10_000L
        const val SERVER_PID = 4242
        const val EXIT_BOUND = 150L
    }
}
