package com.brahamchari.demoplugin.utils

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object MyPluginIcons {
    @JvmField
    val AutoQA: Icon = IconLoader.getIcon("icons/auto_qa_icon.png", MyPluginIcons::class.java)

    @JvmField
    val AIStars: Icon = IconLoader.getIcon("icons/ai_stars.svg", MyPluginIcons::class.java)

    @JvmField
    val ReRun: Icon = IconLoader.getIcon("icons/re_run_icon.svg", MyPluginIcons::class.java)

    @JvmField
    val Bookmark: Icon = IconLoader.getIcon("icons/bookmark_icon.svg", MyPluginIcons::class.java)

    @JvmField
    val Success: Icon = IconLoader.getIcon("icons/success_icon.svg", MyPluginIcons::class.java)

    @JvmField
    val Error: Icon = IconLoader.getIcon("icons/error_icon.svg", MyPluginIcons::class.java)
}
