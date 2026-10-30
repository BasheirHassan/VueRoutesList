package com.veuroutes.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "VueRoutesSettings",
    storages = [Storage("vueRoutes.xml")],
)
@Service(Service.Level.APP)
class VueRoutesSettings : PersistentStateComponent<VueRoutesSettings.State> {

    class State {
        var autoRefresh: Boolean = true
        var excludedFolders: String = DEFAULT_EXCLUDED_FOLDERS
        var maxFileSizeKb: Int = DEFAULT_MAX_FILE_SIZE_KB
    }

    private val myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState.autoRefresh = state.autoRefresh
        myState.excludedFolders = state.excludedFolders
        myState.maxFileSizeKb = state.maxFileSizeKb.coerceIn(MIN_FILE_SIZE_KB, MAX_FILE_SIZE_KB)
    }

    val autoRefresh: Boolean
        get() = myState.autoRefresh

    val maxFileSizeKb: Int
        get() = myState.maxFileSizeKb.coerceIn(MIN_FILE_SIZE_KB, MAX_FILE_SIZE_KB)

    val excludedFolders: String
        get() = myState.excludedFolders

    fun excludedFolderNames(): Set<String> = myState.excludedFolders
        .split(',', ';')
        .asSequence()
        .map { it.trim().trim('/', '\\').lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    fun update(autoRefresh: Boolean, excludedFolders: String, maxFileSizeKb: Int) {
        myState.autoRefresh = autoRefresh
        myState.excludedFolders = excludedFolders.trim()
        myState.maxFileSizeKb = maxFileSizeKb.coerceIn(MIN_FILE_SIZE_KB, MAX_FILE_SIZE_KB)
    }

    companion object {
        const val DEFAULT_EXCLUDED_FOLDERS =
            "node_modules, dist, build, out, .git, .nuxt, .output, .next, coverage, public, test-results, playwright-report"
        const val DEFAULT_MAX_FILE_SIZE_KB = 512
        const val MIN_FILE_SIZE_KB = 32
        const val MAX_FILE_SIZE_KB = 10_240

        fun getInstance(): VueRoutesSettings =
            ApplicationManager.getApplication().getService(VueRoutesSettings::class.java)
    }
}
