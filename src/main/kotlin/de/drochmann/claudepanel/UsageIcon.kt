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
 * Two limits in one glyph: the ring fills with the five-hour window, the dot in its centre
 * takes the colour of the weekly one.
 *
 * Numbers would need reading, this is taken in at a glance - and the row has room for
 * nothing else anyway. The exact figures stay in the tooltip.
 *
 * The two windows are shown differently on purpose. The five-hour one moves during a
 * sitting, so it gets the shape that shows a value; the weekly one is background weather
 * and only needs to say whether it is getting tight, so it gets a colour.
 *
 * `null` in either means "not known yet" and draws nothing rather than zero: an empty
 * track reads as absence, and a missing dot as a window that was not reported.
 */
class UsageIcon : Icon {

    /** The arc - the five-hour window, or whatever else is highest if labels failed. */
    var ringPercent: Int? = null

    /** The centre dot - the weekly window. */
    var weekPercent: Int? = null

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

            // Half the stroke lies inside the path, so the free interior starts that much
            // further in. The two pixels on top are what keeps the glyph readable when arc
            // and dot land in the same colour band - without them the two merge into one
            // disc and the arc stops saying anything.
            weekPercent?.coerceIn(0, 100)?.let { week ->
                val margin = (thickness / 2f).toInt() + JBUI.scale(2)
                val dot = diameter - 2 * margin
                if (dot > 0) {
                    g2.color = colorFor(week)
                    g2.fillOval(x + inset + margin, y + inset + margin, dot, dot)
                }
            }

            val value = ringPercent?.coerceIn(0, 100) ?: return
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
        /** 14 left too little room between arc and dot once both were drawn. */
        const val SIZE = 16
    }
}
