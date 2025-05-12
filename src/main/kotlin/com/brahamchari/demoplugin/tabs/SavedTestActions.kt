package com.brahamchari.demoplugin.tabs // Or a more specific package

import com.brahamchari.demoplugin.custom.RoundedPanel
import com.brahamchari.demoplugin.models.TestExecutionLog
import com.brahamchari.demoplugin.models.TestStatus
import com.brahamchari.demoplugin.presenter.MainTestCasePresenter
import com.brahamchari.demoplugin.utils.MyPluginIcons
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*

// Interface for actions triggered from SavedTestsPanel that TestToolWindowContent will handle
interface SavedTestActions {
    fun onReRunTestRequest(userInput: String, originalLog: TestExecutionLog? = null, activateTestTab: Boolean = true)
}

// Interface for SavedTestsPanel to be a view for the presenter
interface SavedTestsView {
    fun displaySavedTests(tests: List<TestExecutionLog>)
    fun showSavedTestsLoading(isLoading: Boolean)
    fun displaySavedTestsError(errorMessage: String)
}

class SavedTestsPanel(
    private val project: Project,
    parentDisposable: Disposable, // Parent disposable (e.g., tool window's disposable)
    private val presenter: MainTestCasePresenter,
    private val savedTestActionsHandler: SavedTestActions
) : JPanel(BorderLayout()), SavedTestsView, Disposable { // ADDED Disposable back here

    private val log = Logger.getInstance(SavedTestsPanel::class.java)
    @Volatile private var isLoadingData = false
    private val contentPanel = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8)))

    private val headerPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(5, 10)
        background = UIUtil.getPanelBackground()
        isOpaque = true
        val titleLabel = JBLabel("Saved Test Cases").apply {
            font = JBFont.h3().asBold()
            foreground = UIUtil.getLabelForeground()
        }
        add(titleLabel, BorderLayout.WEST)
    }

    init {
        presenter.registerSavedTestsView(this)
//        contentPanel.add(headerPanel, BorderLayout.NORTH) // Add header initially here

        add(contentPanel, BorderLayout.CENTER)
        // CORRECTED: Register this panel instance with the parentDisposable
        Disposer.register(parentDisposable, this)
    }

    /**
     * Called when this panel's tab is selected or becomes visible.
     * Triggers a data refresh if not already loading.
     */
    fun onTabSelected() {
        log.info("SavedTestsPanel: Tab selected.")
        if (!isLoadingData) {
            log.info("SavedTestsPanel: Not currently loading. Initiating data refresh.")
            isLoadingData = true
            presenter.loadTestLogsForSavedTestsTab()
        } else {
            log.info("SavedTestsPanel: Already loading data. Refresh request on tab selection ignored.")
        }
    }

    override fun showSavedTestsLoading(isLoading: Boolean) {
        ApplicationManager.getApplication().invokeLater {
            contentPanel.removeAll() // Clear all contents from SavedTestsPanel (including the header)
//            contentPanel.add(headerPanel, BorderLayout.NORTH) // Re-add the header to NORTH

            if (isLoading) {
                this.isLoadingData = true
                val loadingLabel = JBLabel("Loading saved tests...", AnimatedIcon.Default(), SwingConstants.CENTER).apply {
                    foreground = UIUtil.getLabelForeground()
                }
                contentPanel.add(loadingLabel, BorderLayout.CENTER)
            }
            contentPanel.revalidate() // UNCOMMENTED - Crucial for layout
            contentPanel.repaint() // UNCOMMENTED - Crucial for redraw
            if(isLoading) log.info("SavedTestsPanel: Showing loading indicator.")
        }
    }

    override fun displaySavedTests(tests: List<TestExecutionLog>) {
        ApplicationManager.getApplication().invokeLater {
            isLoadingData = false
            contentPanel.removeAll()

            if (tests.isEmpty()) {
                displaySavedTestsError("No saved tests found.")
                return@invokeLater
            }

            val listItemsPanel = JBPanel<JBPanel<*>>().apply {
                layout = VerticalLayout(JBUI.scale(8))
                isOpaque = false
                border = JBUI.Borders.empty(16)
            }

            tests.forEach { test ->
                listItemsPanel.add(createSavedTestRowGui(test))
            }

            val scrollPane = JBScrollPane(listItemsPanel).apply { // Use wrapperPanel as viewport view
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                border = JBUI.Borders.empty()
            }

            contentPanel.add(scrollPane, BorderLayout.CENTER)
            contentPanel.revalidate()
            contentPanel.repaint()
        }
    }

    override fun displaySavedTestsError(errorMessage: String) {
        ApplicationManager.getApplication().invokeLater {
            contentPanel.removeAll() // Clear all contents
//            contentPanel.add(headerPanel, BorderLayout.NORTH) // Re-add header

            isLoadingData = false
            val errorLabel = JBLabel(errorMessage, AllIcons.General.ErrorDialog, SwingConstants.CENTER).apply {
                font = JBFont.label().asItalic()
                foreground = JBColor.RED
                border = JBUI.Borders.empty(20)
            }
            contentPanel.add(errorLabel, BorderLayout.CENTER) // Add error label to CENTER of SavedTestsPanel
            contentPanel.revalidate() // UNCOMMENTED - Crucial for layout
            contentPanel.repaint() // UNCOMMENTED - Crucial for redraw
            log.warn("SavedTestsPanel: Showing error message - $errorMessage")
        }
    }

    private val borderColor = JBColor(0x646464, 0x646464)

    private fun createSavedTestRowGui(test: TestExecutionLog): JPanel {
        val rowPanel = RoundedPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))).apply {
            border = JBUI.Borders.compound(
                RoundedLineBorder(borderColor, 16, 1),
                JBUI.Borders.empty(8)
            )

            maximumSize = Dimension(JBUI.scale(650), Short.MAX_VALUE.toInt())
            minimumSize = Dimension(JBUI.scale(10), JBUI.scale(35))
        }

        val statusIconLabel = JBLabel().apply {
            icon = when (test.status) {
                TestStatus.PASSED -> MyPluginIcons.Success
                TestStatus.FAILED -> MyPluginIcons.Error
                else -> AllIcons.General.Warning
            }
            toolTipText = "Status: ${test.status} on ${test.startTime}"
        }

        val textAndTimestampPanel = JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(0), JBUI.scale(2))).apply {
            maximumSize = Dimension(JBUI.scale(650), Short.MAX_VALUE.toInt())
            minimumSize = Dimension(JBUI.scale(10), JBUI.scale(10))

            val testTitleArea = JBTextArea().apply {
                text = test.userInput
                lineWrap = true
                wrapStyleWord = true
                isEditable = false
                isOpaque = true
//                foreground = JBUI.CurrentTheme.Label.foreground()
                font = JBFont.h3().asBold()
            }
            val testDescArea = JBTextArea().apply {
                text = test.executionResult?.introduction ?: "Introduction not available"
                lineWrap = true
                wrapStyleWord = true
                isEditable = false
                isOpaque = true
//                foreground = JBUI.CurrentTheme.Label.foreground()
                font = JBUI.Fonts.label()
            }
            val timestampLabel = JBLabel(getFormattedTime(test.startTime)).apply {
                this.font = JBFont.small()
                foreground = UIUtil.getContextHelpForeground()
            }
            add(timestampLabel, BorderLayout.NORTH)
            add(testTitleArea, BorderLayout.CENTER)
            add(testDescArea, BorderLayout.SOUTH)
        }

        val rerunButton = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText = "Re-run this test without AI"
            margin = JBUI.emptyInsets()
            isFocusPainted = false
            isContentAreaFilled = false
            isOpaque = false
            addActionListener {
                 savedTestActionsHandler.onReRunTestRequest(test.userInput, test)
            }
        }

        val rerunWithAIButton = JButton(MyPluginIcons.AIStars).apply {
            toolTipText = "Re-run this test with AI"
            margin = JBUI.emptyInsets()
            isFocusPainted = false
            isContentAreaFilled = false
            isOpaque = false
            addActionListener {
                savedTestActionsHandler.onReRunTestRequest(test.userInput)
            }
        }

        val buttonsPanel = JBPanel<JBPanel<*>>(VerticalLayout(JBUI.scale(8))).apply {
            add(rerunButton)
            add(rerunWithAIButton)
        }

        rowPanel.add(statusIconLabel, BorderLayout.WEST)
        rowPanel.add(textAndTimestampPanel, BorderLayout.CENTER)
        rowPanel.add(buttonsPanel, BorderLayout.EAST)
        return rowPanel
    }

    override fun dispose() { // Implement dispose method for Disposable interface
        presenter.unregisterSavedTestsView(this)
        log.info("SavedTestsPanel disposed for project: ${project.name}")
    }

    private fun getFormattedTime(timestamp: Long): String {
        val time = Date(timestamp)
        val timestampFormat = SimpleDateFormat("d MMM '▪' h:mm a", Locale.getDefault())
        val timestampStrRaw = timestampFormat.format(time)
        return timestampStrRaw
            .replace(" am", " AM")
            .replace(" pm", " PM")
    }
}