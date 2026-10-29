package io.github.xenonisawesome.rr.core

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

class RrSettingsConfigurable : BoundConfigurable("rr (Record & Replay)") {
    override fun createPanel(): DialogPanel {
        val settings = RrSettings.getInstance()
        return panel {
            if (!RrPlatform.isSupported) {
                row { comment(RrPlatform.UNSUPPORTED_MESSAGE) }
            }
            row("rr binary:") {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.singleFile().withTitle("Select rr Binary"),
                    null,
                ).columns(COLUMNS_LARGE)
                    .bindText(settings::rrPath) { settings.rrPath = it }
                    .comment("Leave empty to find <code>rr</code> on PATH.")
            }
            row("Trace directory:") {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Select rr Trace Directory"),
                    null,
                ).columns(COLUMNS_LARGE)
                    .bindText(settings::traceDir) { settings.traceDir = it }
                    .comment(
                        "Leave empty for rr's default (<code>\$_RR_TRACE_DIR</code>, else " +
                            "<code>~/.local/share/rr</code>). Current default: <code>" +
                            RrTraceDirs.defaultRoot() + "</code>",
                    )
            }
            row("Extra <code>rr record</code> flags:") {
                textField().columns(COLUMNS_LARGE)
                    .bindText(settings::recordFlags) { settings.recordFlags = it }
                    .comment("For example <code>-h</code> enables chaos mode.")
            }
            row {
                val result = comment("").component
                button("Check rr") {
                    result.text = when (val r = RrBinary.resolve(RrSettings.getInstance().rrPath.ifEmpty { null })) {
                        is RrBinary.Resolution.Found -> "Found ${r.path} (rr ${r.version})"
                        is RrBinary.Resolution.Problem -> r.message
                    }
                }
            }
        }
    }
}
