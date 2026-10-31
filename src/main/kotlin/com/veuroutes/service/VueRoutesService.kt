package com.veuroutes.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.veuroutes.model.VueRoute
import com.veuroutes.scan.ScanResult
import com.veuroutes.scan.VueRoutesScanner
import com.veuroutes.settings.VueRoutesSettings
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class VueRoutesService(private val project: Project) : Disposable {

    data class Snapshot(
        val routes: List<VueRoute> = emptyList(),
        val routerFileCount: Int = 0,
        val durationMs: Long = 0,
        val error: String? = null,
    )

    interface Listener {
        fun onScanStarted() {}

        fun onScanFinished(snapshot: Snapshot) {}
    }

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    @Volatile
    private var snapshot: Snapshot? = null

    @Volatile
    private var scanning = false

    private var rescanRequested = false

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: MutableList<out VFileEvent>) {
                    if (!VueRoutesSettings.getInstance().autoRefresh) return
                    if (snapshot == null) return
                    val relevant = events.any { event ->
                        val path = event.path.replace('\\', '/')
                        val name = path.substringAfterLast('/')
                        when {
                            SCAN_EXTENSIONS.any { path.endsWith(".$it") } -> true
                            name in CONFIG_FILE_NAMES -> true
                            path.endsWith(".vue") -> event is VFileCreateEvent ||
                                event is VFileDeleteEvent ||
                                event is VFileMoveEvent
                            else -> false
                        }
                    }
                    if (!relevant) return

                    alarm.cancelAllRequests()
                    alarm.addRequest(
                        Runnable {
                            if (!project.isDisposed) refresh()
                        },
                        800L,
                    )
                }
            },
        )
    }

    fun addListener(listener: Listener): Disposable {
        listeners.add(listener)
        snapshot?.let { cached ->
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed && listeners.contains(listener)) {
                    listener.onScanFinished(cached)
                }
            }
        }
        return Disposable { listeners.remove(listener) }
    }

    fun currentSnapshot(): Snapshot? = snapshot

    fun ensureLoaded() {
        if (snapshot == null && !scanning) refresh()
    }

    fun refresh() {
        if (project.isDisposed) return

        if (DumbService.isDumb(project)) {
            val indexing = Snapshot(error = INDEXING_MESSAGE)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                snapshot = indexing
                listeners.forEach { it.onScanFinished(indexing) }
            }
            DumbService.getInstance(project).runWhenSmart {
                if (!project.isDisposed) refresh()
            }
            return
        }

        synchronized(lock) {
            if (scanning) {
                rescanRequested = true
                return
            }
            scanning = true
        }

        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                listeners.forEach { it.onScanStarted() }
            }
        }

        AppExecutorUtil.getAppExecutorService().execute { performScan() }
    }

    private fun performScan() {
        val startedAt = System.currentTimeMillis()
        val result = try {
            val scan = ReadAction.computeBlocking<ScanResult, Exception> {
                if (project.isDisposed) {
                    ScanResult(emptyList(), 0)
                } else {
                    VueRoutesScanner(project).scan()
                }
            }
            Snapshot(
                routes = scan.routes,
                routerFileCount = scan.routerFileCount,
                durationMs = System.currentTimeMillis() - startedAt,
            )
        } catch (_: IndexNotReadyException) {
            DumbService.getInstance(project).runWhenSmart {
                if (!project.isDisposed) refresh()
            }
            Snapshot(
                durationMs = System.currentTimeMillis() - startedAt,
                error = INDEXING_MESSAGE,
            )
        } catch (_: ProcessCanceledException) {
            Snapshot(
                durationMs = System.currentTimeMillis() - startedAt,
                error = "Route scan was canceled.",
            )
        } catch (e: Exception) {
            LOG.warn("Vue Routes List scan failed", e)
            Snapshot(
                durationMs = System.currentTimeMillis() - startedAt,
                error = e.message ?: e.javaClass.simpleName,
            )
        }

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            var scanAgain = false
            synchronized(lock) {
                snapshot = result
                scanning = false
                if (rescanRequested) {
                    rescanRequested = false
                    scanAgain = true
                }
            }

            listeners.forEach { it.onScanFinished(result) }
            if (scanAgain) refresh()
        }
    }

    override fun dispose() {
        Disposer.dispose(alarm)
        listeners.clear()
    }

    companion object {
        const val INDEXING_MESSAGE = "Indexing is in progress. Vue Routes List will refresh automatically."

        private val LOG = Logger.getInstance(VueRoutesService::class.java)
        private val SCAN_EXTENSIONS = listOf("js", "ts", "mjs", "cjs", "mts", "cts", "jsx", "tsx")
        private val CONFIG_FILE_NAMES = setOf(
            "tsconfig.json",
            "jsconfig.json",
            "tsconfig.app.json",
            "tsconfig.base.json",
        )
    }
}
