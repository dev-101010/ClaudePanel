package de.drochmann.claudepanel

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/**
 * A ring that fills with how much of the limit is used.
 *
 * A number would need reading, a ring is taken in at a glance - and the button has room
 * for nothing else anyway. The exact figures stay in the tooltip.
 *
 * [percent] `null` means "not known yet": then only the empty track is drawn, which reads
 * as absence rather than as zero usage.
 */
class UsageIcon : Icon {

    var percent: Int? = null

    override fun getIconWidth(): Int = JBUI.scale(SIZE)

    override fun getIconHeight(): Int = JBUI.scale(SIZE)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val thickness = JBUI.scale(2).toFloat()
            g2.stroke = BasicStroke(thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

            // Half the stroke sits outside the path, so inset by that much or the ring is
            // clipped at the icon's edge.
            val inset = (thickness / 2f).toInt() + JBUI.scale(1)
            val diameter = getIconWidth() - 2 * inset

            g2.color = UIUtil.getContextHelpForeground()
            g2.drawOval(x + inset, y + inset, diameter, diameter)

            val value = percent?.coerceIn(0, 100) ?: return
            if (value == 0) return
            g2.color = colorFor(value)
            // Starting at twelve o'clock and going clockwise, like a clock face.
            g2.drawArc(x + inset, y + inset, diameter, diameter, 90, -(value * 360 / 100))
        } finally {
            g2.dispose()
        }
    }

    /** Calm while there is room, insistent once it gets tight. */
    private fun colorFor(percent: Int): JBColor = when {
        percent < 60 -> JBColor(0x2E7D32, 0x6FBF73)
        percent < 85 -> JBColor(0xB8730B, 0xE8A33D)
        else -> JBColor(0xC7222E, 0xFF6B68)
    }

    private companion object {
        const val SIZE = 14
    }
}
