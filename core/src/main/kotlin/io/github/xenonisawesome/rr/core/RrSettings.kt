package io.github.xenonisawesome.rr.core

import com.intellij.execution.configurations.ParametersList
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/** Application-wide rr settings. Empty strings mean "use the default". */
@Service(Service.Level.APP)
@State(name = "RrPluginSettings", storages = [Storage("rr-plugin.xml")])
class RrSettings : PersistentStateComponent<RrSettings.State> {
    class State {
        /** Path of the rr binary; empty = look it up on PATH. */
        var rrPath: String = ""

        /** Trace root; empty = rr's own default (honours _RR_TRACE_DIR). */
        var traceDir: String = ""

        /** Extra flags for `rr record`, e.g. `-h` for chaos mode. */
        var recordFlags: String = ""
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    var rrPath: String
        get() = state.rrPath.trim()
        set(value) { state.rrPath = value.trim() }

    var traceDir: String
        get() = state.traceDir.trim()
        set(value) { state.traceDir = value.trim() }

    var recordFlags: String
        get() = state.recordFlags.trim()
        set(value) { state.recordFlags = value.trim() }

    /** [recordFlags] split like a shell would (quotes respected). */
    fun recordFlagList(): List<String> = ParametersList.parse(recordFlags).toList()

    companion object {
        fun getInstance(): RrSettings = ApplicationManager.getApplication().getService(RrSettings::class.java)
    }
}
