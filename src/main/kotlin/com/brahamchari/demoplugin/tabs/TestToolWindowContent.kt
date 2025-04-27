package com.brahamchari.demoplugin.tabs

import com.android.ddmlib.IDevice
import com.brahamchari.demoplugin.custom.RoundedPanel
import com.brahamchari.demoplugin.di.TestCaseInjector
import com.brahamchari.demoplugin.models.*
import com.brahamchari.demoplugin.presenter.MainTestCasePresenter
import com.brahamchari.demoplugin.repository.MainTestCaseView
import com.brahamchari.demoplugin.utils.MyPluginIcons
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.*
import com.intellij.ui.components.*
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener


class TestToolWindowContent(
    private val project: Project,
    private val disposable: Disposable,
    private val testCaseInjector: TestCaseInjector
) : MainTestCaseView, JPanel(BorderLayout()) {

    private val log = Logger.getInstance(TestToolWindowContent::class.java)

    // --- UI Components ---
    private val adbDeviceModel = CollectionComboBoxModel<IDevice>()
    private val devicesComboBox = ComboBox(adbDeviceModel)
    private val statusLabel = JBLabel("Initializing...", AllIcons.General.Information, SwingConstants.LEFT)

    // Map Log ID to a Pair: the outer bot panel shell and its inner content panel
    private val botResponsePanels = ConcurrentHashMap<String, Pair<JPanel, JPanel>>()
    private lateinit var chatLogPanel: JPanel // Initialized in createJBListPanel
    private lateinit var chatScrollPane: JBScrollPane // Initialized in createJBListPanel

    private lateinit var testCaseInputArea: JBTextArea
    private lateinit var inputScrollPane: JBScrollPane

    private lateinit var runStopButton: JButton

    // Constants for resizing behaviour
    private val MIN_INPUT_AREA_ROWS = 3
    private val MAX_INPUT_AREA_ROWS = 6 // Max height before scrolling starts

    // --- Presenter and State ---
    private val presenter: MainTestCasePresenter = testCaseInjector.getTestCasePresenter(this, project, disposable)

    init {
        border = JBUI.Borders.empty()

        // 1. Create and Add Header Panel directly to the NORTH
        add(createHeaderPanel(), BorderLayout.NORTH)

        // 2. Create and Add Main Chat/Log Content Area
        add(createChatLogPanel(), BorderLayout.CENTER)

        // 3. Create and Add Status Bar
        add(createBottomPanel(), BorderLayout.SOUTH)

        // 4. Setup ComboBox Renderer (Purely View logic)
        setupAdbDeviceRenderer()

        // 5. Register cleanup
        Disposer.register(disposable) {
            log.info("Cleanup: ${project.name}")
        }
    }

    // --- UI Creation Methods ---
    private fun createHeaderPanel(): JComponent {
        // Panel to hold components, aligned to the right
        val headerPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, JBUI.scale(5), 0))
        headerPanel.border = JBUI.Borders.empty(5, 10) // Padding

        devicesComboBox.toolTipText = "Select target ADB device"
        devicesComboBox.setMinimumAndPreferredWidth(JBUI.scale(250))
        devicesComboBox.addActionListener {
            val selected = devicesComboBox.selectedItem as? IDevice
            log.info("Device selected: ${selected?.serialNumber}")
            presenter.onDeviceSelected(devicesComboBox.selectedItem as? IDevice)
        }

        val refreshAction = object : DumbAwareAction(
            "Refresh Devices",
            "Reload the list of connected ADB devices",
            AllIcons.Actions.Refresh
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                presenter.onRefreshDevicesClicked()
            }
            // Optional: Update based on whether ADB is available?
            // override fun update(e: AnActionEvent) {
            //    e.presentation.isEnabled = ...
            // }
        }

        val actionGroup = DefaultActionGroup(refreshAction)
        val actionToolbar = ActionManager.getInstance().createActionToolbar(
            ActionPlaces.TOOLWINDOW_TITLE,
            actionGroup,
            true
        ).apply {
            targetComponent = headerPanel
            setReservePlaceAutoPopupIcon(false) // No dropdown needed
            component.isOpaque = false // Match background
        }

        // Add components to header
        headerPanel.add(devicesComboBox)
        headerPanel.add(actionToolbar.component)

        // Add border below header
        headerPanel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBUI.CurrentTheme.ToolWindow.borderColor(), 0, 0, 1, 0),
            JBUI.Borders.empty(5, 10)
        )
        return headerPanel
    }


    /** Creates the main scrollable panel that will hold the chat/log messages. */
    private fun createChatLogPanel(): JPanel {
        val mainPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty()
        }
        chatLogPanel = JBPanel<JBPanel<*>>().apply {
            layout = VerticalLayout(0)
            border = JBUI.Borders.empty(10)
        }
        chatScrollPane = JBScrollPane(chatLogPanel).apply {
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = JBUI.Borders.empty()
        }
        mainPanel.add(chatScrollPane, BorderLayout.CENTER)
        return mainPanel
    }

    /** Creates the main bottom panel containing the input area and status bar. */
    private fun createBottomPanel(): JComponent {
        val bottomPanel = JBPanel<JBPanel<*>>(BorderLayout())
        // Add input area (TextArea + Button) to the center part of the bottom panel
        bottomPanel.add(createInputAreaPanel(), BorderLayout.CENTER)
        // Add the actual status label bar below the input area
        bottomPanel.add(createActualStatusBar(), BorderLayout.SOUTH)
        return bottomPanel
    }

    /** Creates the panel with the icon, text input, and the run/stop button. */
    private fun createInputAreaPanel(): JComponent {
        // --- Main Container Panel ---
        val containerPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            val topBorder = JBUI.Borders.customLine(
                JBUI.CurrentTheme.ToolWindow.borderColor(), 1, 0, 0, 0
            )
            val paddingBorder = JBUI.Borders.empty(8)
            border = JBUI.Borders.compound(topBorder, paddingBorder)
        }

        // --- Text Input Area (Center) ---
        testCaseInputArea = JBTextArea().apply {
            rows = MIN_INPUT_AREA_ROWS
            lineWrap = true
            wrapStyleWord = true
            emptyText.text = "What would you like to do?"
            toolTipText = "Enter the natural language test case to execute"
            margin = JBUI.insets(5)
            minimumSize = Dimension(JBUI.scale(100), calculateScrollPaneSize(MIN_INPUT_AREA_ROWS).height)
        }

        // Wrap text area in scroll pane
        inputScrollPane = JBScrollPane(testCaseInputArea).apply {
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = calculateScrollPaneSize(MIN_INPUT_AREA_ROWS)
        }

        // Add Document Listener (same as before)
        testCaseInputArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) {
                handleUpdate()
            }

            override fun removeUpdate(e: DocumentEvent?) {
                handleUpdate()
            }

            override fun changedUpdate(e: DocumentEvent?) {
                handleUpdate()
            }

            private fun handleUpdate() {
                ApplicationManager.getApplication()
                    .invokeLater { if (!Disposer.isDisposed(disposable)) updateScrollPaneHeight() }
            }
        })

        containerPanel.add(inputScrollPane, BorderLayout.CENTER)

        // --- Bottom Control Panel (Icon + Button, aligned SOUTH) ---
        val controlsPanel = JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(5), 0))
        val iconLabel = JBLabel(AllIcons.Actions.AddFile).apply { border = JBUI.Borders.emptyBottom(3) }
        controlsPanel.add(iconLabel, BorderLayout.WEST)
        runStopButton = JButton("Send")
        runStopButton.toolTipText = "Send the command/start the test"
        controlsPanel.add(runStopButton, BorderLayout.EAST)
        containerPanel.add(controlsPanel, BorderLayout.SOUTH)

        // Initialize button state
        ApplicationManager.getApplication().invokeLater {
            if (!Disposer.isDisposed(disposable)) {
                updateRunStopButtonState(TestRunningStatus.STOPPED)
            }
        }

        return containerPanel
    }

    /** Calculates the preferred Dimension for the inputScrollPane based on row count. */
    private fun calculateScrollPaneSize(rowCount: Int): Dimension {
        // Ensure textArea is initialized before calculating
        if (!::testCaseInputArea.isInitialized) {
            log.warn("calculateScrollPaneSize called before testCaseInputArea initialized")
            return Dimension(JBUI.scale(200), JBUI.scale(30)) // Return some default fallback
        }

        val fm = testCaseInputArea.getFontMetrics(testCaseInputArea.font)
        val rowHeight = fm.height
        val insetHeight = testCaseInputArea.insets.top + testCaseInputArea.insets.bottom
        val preferredHeight = rowHeight * rowCount + insetHeight + JBUI.scale(10) // Add padding

        // Width 0 means the layout manager determines the width.
        // We only calculate the desired height.
        val preferredWidth = 0

        return Dimension(preferredWidth, preferredHeight)
    }

    /** Updates the scroll pane's preferred height based on text content, up to MAX_INPUT_AREA_ROWS. */
    private fun updateScrollPaneHeight() {
        // Calculate the preferred height needed by the text area content
        val currentTextAreaPreferredHeight = testCaseInputArea.preferredSize.height

        // Calculate the maximum height based on MAX_INPUT_AREA_ROWS
        val maxHeight = calculateScrollPaneSize(MAX_INPUT_AREA_ROWS).height
        // Calculate the minimum height based on MIN_INPUT_AREA_ROWS
        val minHeight = calculateScrollPaneSize(MIN_INPUT_AREA_ROWS).height

        // Determine the target height, capped by min/max
        val targetHeight = minHeight.coerceAtLeast(currentTextAreaPreferredHeight.coerceAtMost(maxHeight))

        // Update scroll pane preferred size only if it needs to change
        if (inputScrollPane.preferredSize.height != targetHeight) {
            // Keep current width, only change height
            val currentWidth = inputScrollPane.preferredSize.width
            inputScrollPane.preferredSize = Dimension(currentWidth, targetHeight)

            // Crucial: Trigger re-layout of the parent container
            val parentContainer = inputScrollPane.parent ?: this // Get the container (inputPanel or main panel)
            parentContainer.revalidate()
            parentContainer.repaint()
            log.debug("Resized input area scroll pane height to: $targetHeight")
        }
    }

    /** Creates the actual status bar panel holding just the status label. */
    private fun createActualStatusBar(): JComponent {
        val statusBar = JBPanel<JBPanel<*>>(BorderLayout())
        // Add border above the status label
        statusBar.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBUI.CurrentTheme.ToolWindow.borderColor(), 1, 0, 0, 0),
            JBUI.Borders.empty(6, 8) // Padding for status label
        )
        // statusLabel is initialized as a class member
        statusBar.add(statusLabel, BorderLayout.CENTER)
        return statusBar
    }

    // --- ADB Device Logic (Adapted from previous example) ---

    // Configure renderer for IDevice objects
    private fun setupAdbDeviceRenderer() {
        devicesComboBox.renderer = SimpleListCellRenderer.create(" No Devices Connected") { device ->
            // Customize how the device is shown (e.g., name, serial, state)
            val name =
                if (device.isEmulator) device.avdName ?: device.serialNumber else device.name ?: device.serialNumber
            val state = " [${device.state}]"
            (name ?: "Unknown") + state
        }
    }

    // --- Helper Methods ---

    private fun openImageInIntelliJ(imagePath: String) {
        // Consider running file system access in background? For now, keep as is.
        try {
            val file = File(imagePath)
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            if (virtualFile != null) {
                FileEditorManager.getInstance(project).openFile(virtualFile, true)
            } else {
                log.warn("Could not find virtual file for: $imagePath")
                // Consider showing notification instead of JOptionPane for better integration
                // Notifications.Bus.notify(...)
                JOptionPane.showMessageDialog(
                    this,
                    "Could not find file: $imagePath",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        } catch (e: Exception) {
            log.error("Error opening image file: $imagePath", e)
            JOptionPane.showMessageDialog(this, "Error opening image: ${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
        }
    }

    // Renamed and updated to handle the new runStopButton
    private fun updateRunStopButtonState(status: TestRunningStatus) {
        // Ensure button is initialized before updating state (should be safe here)
        if (!::runStopButton.isInitialized) return

        // Remove ALL existing action listeners to prevent duplicates
        runStopButton.actionListeners.forEach { runStopButton.removeActionListener(it) }

        log.warn("Update Run stop button state - $status")

        when (status) {
            TestRunningStatus.RUNNING -> {
                runStopButton.text = "Stop"
                runStopButton.icon = AllIcons.Actions.Suspend
                runStopButton.toolTipText = "Stop the current test execution"
                // ** Add listener that delegates STOP action to Presenter **
                runStopButton.addActionListener { presenter.stopRunningTest() }
                testCaseInputArea.isEnabled = false // Disable input while running
            }

            TestRunningStatus.STOPPED -> {
                runStopButton.text = "Send"
                runStopButton.icon = AllIcons.Actions.Execute
                runStopButton.toolTipText = "Send the test case text to execute"
                // ** Add listener that delegates RUN action to Presenter, passing input text **
                runStopButton.addActionListener {
                    val inputText = getTestCaseInputText() // Get text when button is clicked
                    if (inputText.isNotBlank()) {
                        presenter.runTestCase(inputText)
                    } else {
                        // Optionally show a warning if input is empty
                        setStatus("Please enter test case text.", AllIcons.General.Warning)
                        log.warn("Send clicked with empty input.")
                    }
                }
                testCaseInputArea.isEnabled = true // Enable input when stopped
            }

            else -> {}
        }
        runStopButton.revalidate()
        runStopButton.repaint()
    }


    // --- Interface Implementation (MainTestCaseView) ---

    override fun updateTestStatus(testRunningStatus: TestRunningStatus) {
        // (Keep invokeLater for EDT safety)
        ApplicationManager.getApplication().invokeLater({
            if (Disposer.isDisposed(disposable)) return@invokeLater
            log.info("View received test status update: $testRunningStatus")
            updateRunStopButtonState(testRunningStatus) // Update the new button
            // Presenter should handle the statusLabel text via setStatus
        }, ModalityState.any())
    }

    override fun getTestCaseInputText(): String {
        return if (::testCaseInputArea.isInitialized) testCaseInputArea.text else ""
    }

    override fun displayUserInputLog(text: String) {
        ApplicationManager.getApplication().invokeLater({
            // Safety checks
            if (Disposer.isDisposed(disposable) || !::chatLogPanel.isInitialized) return@invokeLater

            // --- Add Timestamp Row ---
            // Add vertical spacing *before* the timestamp if needed
            if (chatLogPanel.componentCount > 0) {
                chatLogPanel.add(Box.createVerticalStrut(JBUI.scale(8))) // Space before entire user message block
            }

            val userChatBubblePanel = getUserChatPanel(text)
            chatLogPanel.add(userChatBubblePanel)

            // --- Final Steps ---
            chatLogPanel.revalidate()
            chatLogPanel.repaint()
            scrollToBottom()

        }, ModalityState.any())
    }

    private fun getUserChatPanel(text: String): JBPanel<JBPanel<*>> {
        val bubbleStatusPanel = getBubbleTopStatus(Date(), "You")

        val bubblePanel = createSimpleMessagePanelBubble(text, Sender.USER)
        val bubbleRowPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false // Transparent row
            alignmentX = LEFT_ALIGNMENT // Row aligns left in parent
            add(Box.createHorizontalGlue()) // Push bubble right
            add(bubblePanel)               // Add the bubble itself
        }

        val userChatPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(bubbleStatusPanel, BorderLayout.EAST)
            add(Box.createVerticalStrut(JBUI.scale(4)), BorderLayout.CENTER)
            add(bubbleRowPanel, BorderLayout.SOUTH)
        }
        return userChatPanel
    }

    private fun getBubbleTopStatus(time: Date, senderStr: String, senderFirst: Boolean = false): JBPanel<JBPanel<*>> {
        val timestampFormat = SimpleDateFormat("d MMM '▪' h:mm a", Locale.getDefault())
        val timestampStrRaw = timestampFormat.format(time) // Get string with potentially lowercase am/pm
        val timestampStr = timestampStrRaw
            .replace(" am", " AM")
            .replace(" pm", " PM")
        val timestampLabel = JBLabel(timestampStr).apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            alignmentY = TOP_ALIGNMENT
        }

        val senderLabel = JBLabel(senderStr).apply {
            font = JBUI.Fonts.label().deriveFont(Font.BOLD)
            foreground = JBUI.CurrentTheme.Label.foreground()
            alignmentY = TOP_ALIGNMENT // Align label top within its row
        }

        val bubbleStatusPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false // Transparent row
            alignmentX = LEFT_ALIGNMENT // Row aligns left in parent

            if(senderFirst) {
                add(senderLabel)
                add(Box.createHorizontalStrut(JBUI.scale(5)))
            }
            add(timestampLabel)
            if(!senderFirst) {
                add(Box.createHorizontalStrut(JBUI.scale(5)))
                add(senderLabel)
            }
        }
        return bubbleStatusPanel
    }

    private fun createSimpleMessagePanelBubble(text: String, sender: Sender): JPanel {
        val bubblePanel = RoundedPanel(BorderLayout(JBUI.scale(5), 0), JBUI.scale(22)).apply {
            isOpaque = true
            background = userBubbleColor
            border = JBUI.Borders.empty(8, 12)
            alignmentY = Component.TOP_ALIGNMENT
            maximumSize = Dimension(JBUI.scale(650), Short.MAX_VALUE.toInt())
            minimumSize = Dimension(JBUI.scale(100), JBUI.scale(35))
        }

        val messageArea = JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false // Show bubble background
            foreground = JBUI.CurrentTheme.Label.foreground()
            font = JBUI.Fonts.label()
            margin = JBUI.insets(2)
        }
        bubblePanel.add(messageArea, BorderLayout.CENTER)

        return bubblePanel
    }

    override fun addBotResponsePlaceholder(): String {
        val logId = UUID.randomUUID().toString()
        ApplicationManager.getApplication().invokeLater({
            if (Disposer.isDisposed(disposable) || !::chatLogPanel.isInitialized) return@invokeLater
            // Add vertical space before the whole bot message block
            if (chatLogPanel.componentCount > 0) {
                chatLogPanel.add(Box.createVerticalStrut(JBUI.scale(12)))
            }

            // --- Assembly Logic ---

            // 1. Create the separate Status Panel (Timestamp/Sender) - Unchanged
            val statusPanel = getBubbleTopStatus(Date(), "AutoQA", true) // Assuming "AutoQA" is sender
            statusPanel.alignmentX = Component.LEFT_ALIGNMENT // Needed for BoxLayout Y below

            // 2. Create the main Bot Content Bubble Shell - Unchanged
            val botPanelShell = createBotResponsePanelShell()
            val contentPanel = botPanelShell.getClientProperty("contentPanel") as JPanel
            rebuildBotResponsePanelContent(contentPanel, TestExecutionLog(id = logId, isLoading = true)) // Initial loading
            botPanelShell.alignmentX = Component.LEFT_ALIGNMENT // Needed for BoxLayout Y below

            // 3. Create Vertical Stack Panel (Status + Bubble) - Unchanged
            val contentStackPanel = JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS) // Stack status and bubble vertically
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT // Align stack left if needed by parent

                add(statusPanel) // Status on top
                add(Box.createVerticalStrut(JBUI.scale(4))) // Gap between status and bubble
                add(botPanelShell) // Main bubble below
            }

            // --- 4. Create Outer Row Panel using BorderLayout ---
            // This panel arranges the Icon (WEST) and the Content Stack (CENTER)
            val rowPanel = JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(8), 0)).apply { // HGap 8 between icon and content
                isOpaque = false // Transparent background for the row itself
                // alignmentX is not needed for BorderLayout children in BoxLayout Y parent
            }

            // 5. Create Icon Panel Wrapper (for Top-Left Alignment)
            val iconLabel = JBLabel(MyPluginIcons.AutoQA) // Use your loaded AutoQA icon
            // Wrap the icon label in a panel using BorderLayout and place the label
            // in the NORTH position. This prevents the icon from stretching vertically
            // if the contentStackPanel becomes tall.
            val iconWrapperPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = false // Transparent wrapper
                // Add some padding below the icon if desired
                // border = JBUI.Borders.emptyBottom(2)
                add(iconLabel, BorderLayout.NORTH) // Place icon at the top
            }
            // Add the icon wrapper to the WEST of the row panel
            rowPanel.add(iconWrapperPanel, BorderLayout.WEST)

            // 6. Add Vertical Content Stack to the CENTER
            // It will take up the remaining horizontal space and its preferred vertical space.
            rowPanel.add(contentStackPanel, BorderLayout.CENTER)
            // --- End Assembly ---

            // Store references for updates (Unchanged)
            botResponsePanels[logId] = Pair(botPanelShell, contentPanel)

            // Add the final rowPanel to the chat log
            chatLogPanel.add(rowPanel)
            scrollToBottom()
        }, ModalityState.any())
        return logId
    }

    override fun updateBotResponseLog(logId: String, logData: TestExecutionLog) {
        ApplicationManager.getApplication().invokeLater({
            if (Disposer.isDisposed(disposable)) return@invokeLater
            val panels = botResponsePanels[logId]
            if (panels == null) {
                log.warn("Could not find bot response panel with ID: $logId to update.")
                return@invokeLater
            }
            val (outerPanel, contentPanel) = panels

            // Update the content *within* the inner contentPanel
            rebuildBotResponsePanelContent(contentPanel, logData)

            // Revalidate necessary panels
            contentPanel.revalidate()
            contentPanel.repaint()
            outerPanel.revalidate() // Outer panel size might change
            outerPanel.repaint()
            scrollToBottom()
        }, ModalityState.any())
    }

    override fun clearLogArea() {
        ApplicationManager.getApplication().invokeLater({
            if (Disposer.isDisposed(disposable)) return@invokeLater
            botResponsePanels.clear()
            if (::chatLogPanel.isInitialized) {
//                chatLogPanel.removeAll()
                chatLogPanel.revalidate()
                chatLogPanel.repaint()
                log.info("Chat log area cleared.")
            }
        }, ModalityState.any())
    }

    override fun showAdbDevices(devices: List<IDevice>) {
        ApplicationManager.getApplication().invokeLater({
            if (Disposer.isDisposed(disposable)) return@invokeLater
            log.debug("View updating devices ComboBox with ${devices.size} devices")

            val selectedDeviceBeforeUpdate = devicesComboBox.selectedItem as? IDevice
            // Store serial to handle re-selection correctly even if IDevice instance changes
            val selectedSerialBefore = selectedDeviceBeforeUpdate?.serialNumber

            // Update the underlying model for the ComboBox
            adbDeviceModel.replaceAll(devices)

            // Attempt to re-select the previously selected device
            val deviceToReselect = devices.find { it.serialNumber == selectedSerialBefore }

            // Set the selected item in the ComboBox
            val newSelectedItem = when {
                deviceToReselect != null -> deviceToReselect // Previous selection still exists
                devices.isNotEmpty() -> devices.first()     // Previous gone, select first
                else -> null                                // List is empty
            }
            // Setting selectedItem might trigger the action listener; presenter should handle potential re-entry
            devicesComboBox.selectedItem = newSelectedItem

            // Enable/disable ComboBox based on whether devices are present
            devicesComboBox.isEnabled = devices.isNotEmpty()
            devicesComboBox.repaint() // Force repaint after model/selection change
        }, ModalityState.any())
    }

    override fun showAdbLoading(isLoading: Boolean) {
        // Presenter uses setStatus for text/icon feedback.
        // This method could potentially disable/enable UI elements during loading.
        ApplicationManager.getApplication().invokeLater({
            if (Disposer.isDisposed(disposable)) return@invokeLater
            log.debug("View ADB loading state update: $isLoading")
            // Example: Disable refresh button while loading?
            // val refreshAction = ActionManager.getInstance().getAction(...)
            // refreshAction?.templatePresentation?.isEnabled = !isLoading
        }, ModalityState.any())
    }

    override fun showAdbError(message: String?) {
        // Usually handled by setStatus now, but kept for potential specific error UI changes.
        ApplicationManager.getApplication().invokeLater({
            if (Disposer.isDisposed(disposable)) return@invokeLater
            log.debug("View notified of ADB error (usually shown via setStatus)")
        }, ModalityState.any())
    }

    override fun setStatus(text: String, icon: Icon?) {
        // (Keep invokeLater for EDT safety)
        ApplicationManager.getApplication().invokeLater({
            if (Disposer.isDisposed(disposable)) return@invokeLater
            log.debug("View setting status bar text: '$text'")
            statusLabel.text = " $text" // Use the correct label instance
            statusLabel.icon = icon
        }, ModalityState.any())
    }

    override fun getSelectedDevice(): IDevice? {
        return devicesComboBox.selectedItem as? IDevice
    }

    // --- Message Panel Creation Helpers ---

    // Define colors (adjust as needed, consider theme keys if possible)
    private val userBubbleColor = JBColor(Color(0xE1F5FE), Color(0x3A4C5E)) // Lighter Blue / Darker Blue-Gray
    private val botPanelBackground = JBColor(Color(0xF2F2F2), Color(0x45494E)) // Very Light Gray / Dark Gray
    private val botPanelBorderColor = JBColor(Color(0xE0E0E0), Color(0x54585B)) // Light Gray / Medium Gray
    private val chatLogPanelBackground = JBColor(Color(0xE0E0E0), Color(0x3F424A)) // Light Gray / Medium Gray


    /** Creates the outer shell and inner content panel for a bot response. */
    private fun createBotResponsePanelShell(): JPanel {
        val botResponseContainer = RoundedPanel(BorderLayout(0, JBUI.scale(5))).apply {
            background = botPanelBackground
            border = JBUI.Borders.empty(10) // Padding inside the border
            alignmentX = Component.LEFT_ALIGNMENT
        }

        // Inner Panel for Content (Steps, Loading, Status)
        val innerContentPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false // Transparent, use outerBotPanel's background
            alignmentX = Component.LEFT_ALIGNMENT
        }
        botResponseContainer.add(innerContentPanel, BorderLayout.CENTER)

        // Store reference to inner panel using client property for retrieval
        botResponseContainer.putClientProperty("contentPanel", innerContentPanel)

        return botResponseContainer
    }

    /** Rebuilds the *content* of a bot response panel based on log data. */
    private fun rebuildBotResponsePanelContent(contentPanel: JPanel, logData: TestExecutionLog) {
        contentPanel.removeAll() // Clear previous dynamic content

        // 1. Add Introduction
        if (!logData.botIntroduction.isNullOrBlank()) {
            val introArea = JTextArea(logData.botIntroduction).apply {
                isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false
                foreground = JBUI.CurrentTheme.Label.foreground(); font = JBUI.Fonts.label()
                border = JBUI.Borders.emptyBottom(8)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            contentPanel.add(introArea)
        }

        // 2. Add Steps
        logData.steps.forEach { step ->
            contentPanel.add(createStepPanel(step))
            contentPanel.add(Box.createVerticalStrut(JBUI.scale(10))) // Spacing between steps
        }

        // 3. Add Loading Indicator OR Final Status
        addOrUpdateLoadingIndicator(contentPanel, logData.isLoading && logData.finalStatus == null)
        addOrUpdateFinalStatus(contentPanel, logData.finalStatus, logData.errorMessage)

        // contentPanel.revalidate() // Called by caller (updateBotResponseLog)
        // contentPanel.repaint()
    }

    /** Creates the panel for a single step within the bot response. */
    private fun createStepPanel(step: TestStep): JPanel {
        val stepPanel = JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(15), 0)).apply { // More H gap
            isOpaque = false
            // Add identifiers for optimized updates if needed later
            putClientProperty("isStepPanel", true)
            putClientProperty("stepNumber", step.stepNumber)
            border = JBUI.Borders.emptyBottom(5) // Space below step panel
            alignmentX = Component.LEFT_ALIGNMENT
        }

        // Step Title/Header (Improved Layout)
        val stepHeader = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
        stepHeader.add(JBLabel("Step ${step.stepNumber}:").apply {
            font = JBUI.Fonts.label().deriveFont(Font.BOLD)
        })
        // Add header above the main content for this step
        // stepPanel.add(stepHeader, BorderLayout.NORTH) // Option 1: Title above

        // Content Panel (Screenshot WEST, Details CENTER)
        val stepContentPanel = JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(10), 0)).apply { isOpaque = false }

        // Screenshot on the Left
        val screenshotLabel = createScreenshotLabel(step.screenshotPath)
        screenshotLabel.border =
            JBUI.Borders.customLine(JBUI.CurrentTheme.ToolWindow.borderColor()) // Add border to screenshot
        stepContentPanel.add(screenshotLabel, BorderLayout.WEST)

        // Details on the Right
        val detailsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        // Add Title here if preferred
        detailsPanel.add(JBLabel("Step ${step.stepNumber}:").apply {
            font = JBUI.Fonts.label().deriveFont(Font.BOLD)
            border = JBUI.Borders.emptyBottom(4)
        })

        fun addDetail(label: String, value: String?) {
            if (!value.isNullOrBlank()) { // Only add if value is present
                // Use JBTextArea for potential text wrapping
                val detailArea = JBTextArea("$label: $value").apply {
                    font = JBUI.Fonts.smallFont() // Use smaller font for details
                    foreground = JBUI.CurrentTheme.Label.foreground() // Standard text color
                    isEditable = false
                    lineWrap = true       // Allow wrapping
                    wrapStyleWord = true // Wrap whole words
                    isOpaque = false      // Show parent panel background
                    alignmentX = Component.LEFT_ALIGNMENT // Align text area left

                    // Add a small bottom margin for spacing between detail lines
                    border = JBUI.Borders.emptyBottom(2)
                }
                detailsPanel.add(detailArea) // Add the text area to the vertical detailsPanel
                // If border doesn't provide enough space, add strut:
                // detailsPanel.add(Box.createVerticalStrut(JBUI.scale(2)))
            }
        }

        addDetail("Action", step.action)
        addDetail("Resource ID", step.resourceId)
        addDetail("Bounds", step.bounds)

        stepContentPanel.add(detailsPanel, BorderLayout.CENTER)

        stepPanel.add(stepContentPanel, BorderLayout.CENTER) // Add content below title (if title was NORTH)

        return stepPanel
    }

    /** Adds or removes the loading indicator panel. */
    private fun addOrUpdateLoadingIndicator(contentPanel: JPanel, isLoading: Boolean) {
        // Remove existing first
        findComponentByType(contentPanel, JPanel::class.java) { it.name == "loadingPanel" }?.let {
            contentPanel.remove(
                it
            )
        }

        if (isLoading) {
            val loadingPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                name = "loadingPanel"; isOpaque = false
                add(JBLabel("Processing...", AnimatedIcon.Default(), SwingConstants.LEFT))
                alignmentX = Component.LEFT_ALIGNMENT
            }
            contentPanel.add(loadingPanel)
        }
    }

    /** Adds or removes the final status panel. */
    // Define theme-aware colors (place these with other class properties or constants)
    // Using namedColor provides better theme integration if the keys exist in the specific theme
    private val successColor =
        JBColor.namedColor("Label.successForeground", JBColor(0x4CAF50, 0x6A8759)) // Default Greenish
    private val errorColor =
        JBColor.namedColor("Label.errorForeground", JBColor(0xD50000, 0xFF5252))      // Default Reddish
    private val stoppedColor = JBUI.CurrentTheme.Label.foreground() // Default text color for stopped/neutral

    /**
     * Adds, updates, or removes the final status indicator panel at the bottom
     * of the provided contentPanel. It ensures only one status or loading indicator
     * is present at a time.
     *
     * @param contentPanel The JPanel (using BoxLayout Y_AXIS) to add the status to.
     * @param status The final status (PASSED, FAILED, STOPPED), or null if the process is ongoing or status should be removed.
     * @param errorMessage Optional error message to display for FAILED/STOPPED status.
     */
    private fun addOrUpdateFinalStatus(contentPanel: JPanel, status: TestRunningStatus?, errorMessage: String?) {
        // --- 1. Remove any PREVIOUS final status panel ---
        // Find panel tagged with name "statusPanel" and remove it
        findComponentByType(contentPanel, JPanel::class.java) { it.name == "statusPanel" }?.let {
            contentPanel.remove(it)
            log.debug("Removed existing status panel.")
        }

        // --- 2. Add new status panel IF status is final (PASSED, FAILED, STOPPED) ---
        // We don't add anything here if status is null or RUNNING
        if (status != null && status != TestRunningStatus.RUNNING) {

            // --- 3. Remove loading indicator IF PRESENT ---
            // Ensure loading indicator is removed before adding the final status
            findComponentByType(contentPanel, JPanel::class.java) { it.name == "loadingPanel" }?.let {
                contentPanel.remove(it)
                log.debug("Removed loading indicator panel.")
            }

            // --- 4. Create the new status panel itself ---
            val statusPanel = JPanel(FlowLayout(FlowLayout.CENTER)).apply {
                name = "statusPanel" // Tag for easy removal later
                isOpaque = false    // Let parent background show through
                // Add a separator line above the status message for visual clarity
                border = JBUI.Borders.compound(
                    JBUI.Borders.customLine(botPanelBorderColor, 1, 0, 0, 0), // Top line, theme color
                    JBUI.Borders.emptyTop(8) // Padding between line and text
                )
                alignmentX = Component.LEFT_ALIGNMENT // Consistent alignment in BoxLayout
            }

            // --- 5. Determine Text, Icon, and Color based on status ---
            val statusText: String
            val statusIcon: Icon?
            val statusColor: Color

            when (status) {
                TestRunningStatus.PASSED -> {
                    statusText =
                        errorMessage ?: "Test Case Passed" // Show error message even on pass? Unlikely but possible.
                    statusIcon = AllIcons.General.InspectionsOK // Green check
                    statusColor = successColor
                }

                TestRunningStatus.FAILED -> {
                    statusText = errorMessage ?: "Test Case Failed"
                    statusIcon = AllIcons.General.Error // Red error icon
                    statusColor = errorColor
                }

                TestRunningStatus.STOPPED -> {
                    // Assumes STOPPED means user cancellation or premature end
                    statusText = errorMessage ?: "Test Stopped"
                    statusIcon = AllIcons.Process.Stop // Stop icon
                    statusColor = stoppedColor // Use default text color
                }
                // Should not happen due to the 'if' condition, but added defensively
                TestRunningStatus.RUNNING -> {
                    log.warn("Status panel creation skipped for RUNNING state."); return
                }
            }

            // --- 6. Create the status label ---
            val finalLabel = JBLabel(statusText, statusIcon, SwingConstants.CENTER).apply {
                font = JBUI.Fonts.label().deriveFont(Font.BOLD) // Bold text for emphasis
                foreground = statusColor // Set the calculated color
            }

            // --- 7. Add Label to Panel ---
            statusPanel.add(finalLabel)

            // --- 8. Add Status Panel to the main Content Panel ---
            // Ensure it's added at the end visually
            contentPanel.add(statusPanel)
            log.debug("Added final status panel: $statusText")

        } else {
            // Status is null or RUNNING, ensure no status panel is present
            // (Removal was handled in step 1)
            log.debug("No final status provided or status is RUNNING, ensuring no status panel is shown.")
        }

        // Note: Revalidation of contentPanel and its parents should happen
        // in the calling method (e.g., updateBotResponseLog) after this function returns.
    }

    /** Helper method to scroll the main chat log scroll pane to the bottom. */
    private fun scrollToBottom() {
        // Use invokeLater to ensure scrolling happens after layout updates
        SwingUtilities.invokeLater {
            // Check if the scroll pane has been initialized
            if (::chatScrollPane.isInitialized) {
                val scrollBar: JScrollBar = chatScrollPane.verticalScrollBar
                // Set the scrollbar's value to its maximum possible value
                scrollBar.value = scrollBar.maximum
            }
        }
    }

    /**
     * Creates a JBLabel configured to display a screenshot thumbnail.
     * Shows a placeholder if the path is null or the image fails to load.
     * Makes the label clickable to open the full image.
     *
     * @param path The file path to the screenshot image, or null.
     * @return A configured JBLabel.
     */
    private fun createScreenshotLabel(path: String?): JBLabel {
        // Target dimensions for the thumbnail
        val thumbWidth = JBUI.scale(100)
        val thumbHeight = JBUI.scale(160)

        return JBLabel().apply {
            val imageIcon: ImageIcon? = path?.let { p ->
                try {
                    // Attempt to load the image icon from the file path
                    ImageIcon(p)
                } catch (e: Exception) {
                    log.warn("Failed to load screenshot icon: $p", e)
                    null // Return null if loading fails
                }
            }

            if (imageIcon != null) {
                try {
                    // Image loaded successfully, create a scaled thumbnail
                    val scaledImage: Image = imageIcon.image.getScaledInstance(
                        thumbWidth,
                        thumbHeight,
                        Image.SCALE_SMOOTH // Use smooth scaling algorithm
                    )
                    icon = ImageIcon(scaledImage) // Set the label's icon to the thumbnail
                    toolTipText = "Click to view screenshot: $path"
                } catch (e: Exception) {
                    // Handle potential errors during scaling (less likely)
                    log.warn("Failed to scale image: $path", e)
                    // Fallback to placeholder if scaling fails
                    setupPlaceholder(thumbWidth, thumbHeight)
                    toolTipText = "Could not load screenshot preview"
                }
            } else {
                // Path was null or image loading failed, setup placeholder
                setupPlaceholder(thumbWidth, thumbHeight)
                toolTipText = "Screenshot not available"
            }

            // Common settings for both image and placeholder
            isOpaque = false // Don't paint default label background
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) // Indicate clickable

            // Add click listener only if a valid path was provided
            if (path != null) {
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        log.info("Opening screenshot: $path")
                        openImageInIntelliJ(path) // Call the helper to open in editor
                    }
                })
            }
        }
    }

    /** Helper extension function to configure a JBLabel as a placeholder. */
    private fun JBLabel.setupPlaceholder(width: Int, height: Int) {
        text = "[No Image]"
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
        preferredSize = Dimension(width, height)
        // Use a theme-aware border for the placeholder box
        border = JBUI.Borders.customLine(JBUI.CurrentTheme.ToolWindow.borderColor(), 1)
        foreground = JBUI.CurrentTheme.Label.disabledForeground() // Use disabled text color
    }

    // Helper to find components, reused from previous thought process
    private fun findComponentByType(
        container: Container,
        type: Class<*>,
        predicate: (Component) -> Boolean = { true }
    ): Component? {
        return container.components.find { type.isAssignableFrom(it.javaClass) && predicate(it) }
    }

    private fun findComponentsByType(
        container: Container,
        type: Class<*>,
        predicate: (Component) -> Boolean = { true }
    ): List<Component> {
        return container.components.filter { type.isAssignableFrom(it.javaClass) && predicate(it) }
    }
}