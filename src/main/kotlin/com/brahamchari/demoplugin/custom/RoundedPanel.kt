package com.brahamchari.demoplugin.custom

import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.RenderingHints
import javax.swing.border.Border
import javax.swing.border.EmptyBorder
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI

/**
 * A JBPanel that paints its background with rounded corners.
 * Padding should be added using an EmptyBorder.
 */
class RoundedPanel(
    layout: LayoutManager?,
    private val arc: Int = JBUI.scale(16) // Adjust default roundness here
) : JBPanel<RoundedPanel>(layout) {

    init {
        isOpaque = true // Essential for custom background painting
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = background // Use the panel's set background color
            // Fill the rounded rectangle shape
            g2.fillRoundRect(0, 0, width, height, arc, arc)
        } finally {
            g2.dispose()
        }
        // No super.paintComponent(g) call
    }

    override fun setBorder(border: Border?) {
        // Recommend/ensure only EmptyBorder is used for padding
        if (border is EmptyBorder || border == null) {
            super.setBorder(border)
        } else {
            val insets = border.getBorderInsets(this)
            super.setBorder(EmptyBorder(insets)) // Use padding from border, but make it empty
            // log.warn("Non-EmptyBorder set on RoundedPanel, using EmptyBorder for padding.")
        }
    }
}