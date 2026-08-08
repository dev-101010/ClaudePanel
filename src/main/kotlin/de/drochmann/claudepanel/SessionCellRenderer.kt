package de.drochmann.claudepanel

import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import javax.swing.JList

/** Zeigt Titel und gekuerzte Session-ID. */
class SessionCellRenderer : ColoredListCellRenderer<SessionEntry>() {

    override fun customizeCellRenderer(
        list: JList<out SessionEntry>,
        value: SessionEntry?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        border = JBUI.Borders.empty(2, 4)
        if (value == null) return
        append(value.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        append("  ${value.id.take(8)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        toolTipText = value.id
    }
}
