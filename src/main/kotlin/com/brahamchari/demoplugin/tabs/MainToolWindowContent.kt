package com.brahamchari.demoplugin.tabs
//
//import com.brahamchari.demoplugin.di.TestCaseInjector
//import com.brahamchari.demoplugin.models.AiResponseData
//import com.brahamchari.demoplugin.models.TestCase
//import com.brahamchari.demoplugin.models.TestRunningStatus
//import com.brahamchari.demoplugin.presenter.MainTestCasePresenter
//import com.brahamchari.demoplugin.repository.MainTestCaseView
//import com.intellij.icons.AllIcons
//import com.intellij.openapi.fileEditor.FileEditorManager
//import com.intellij.openapi.ui.ComboBox
//import com.intellij.openapi.vfs.LocalFileSystem
//import com.intellij.openapi.wm.ToolWindow
//import com.intellij.ui.JBColor
//import com.intellij.ui.components.JBList
//import com.intellij.ui.components.JBScrollPane
//import com.intellij.ui.dsl.builder.*
//import com.intellij.ui.table.JBTable
//import com.intellij.util.ui.JBEmptyBorder
//import com.intellij.util.ui.JBUI
//import java.awt.*
//import java.awt.Component.TOP_ALIGNMENT
//import java.awt.event.MouseAdapter
//import java.awt.event.MouseEvent
//import java.io.File
//import java.text.SimpleDateFormat
//import java.util.*
//import javax.swing.*
//import javax.swing.border.LineBorder
//import javax.swing.table.DefaultTableModel
//
//class MainToolWindowContent(
//        private val toolWindow: ToolWindow,
//        private val testCaseInjector: TestCaseInjector
//) : MainTestCaseView {
//    private val contentPanel = JPanel()
//    private var testCaseComboBox: ComboBox<String> = ComboBox<String>()
//    private var devicesComboBox: ComboBox<String> = ComboBox<String>()
//    private var testSendButton: JButton = JButton("Send")
//
//    private val listModel = DefaultListModel<AiResponseData>()
//
//    private val presenter: MainTestCasePresenter by lazy {
//        testCaseInjector.getTestCasePresenter(
//                this,
//                toolWindow.project
//        )
//    }
//
//    private var testRunningStatus = TestRunningStatus.STOPPED
//
//    init {
//        contentPanel.apply {
//            layout = BorderLayout()
//            add(createDropdownPanel(), BorderLayout.NORTH)
////            add(createJBListPanel(), BorderLayout.CENTER)
//            add(createPluginPanel(), BorderLayout.CENTER)
//            add(createPluginPanel(), BorderLayout.CENTER)
//            add(createPanel(), BorderLayout.SOUTH)
//        }
//        presenter.getAllAdbDevices()
//    }
//
//
//    fun createPluginPanel(): JPanel {
//        val mainPanel = JPanel()
//        mainPanel.layout = BoxLayout(mainPanel, BoxLayout.Y_AXIS)
//
//        // Row 1: Icon and Text Content (with vertical BoxLayout)
//        val row1 = JPanel()
//        row1.layout = BoxLayout(row1, BoxLayout.X_AXIS)
//
//        // Icon Panel with Border
//        val iconPanel = JPanel()
//        iconPanel.preferredSize = Dimension(50, 50)
//        iconPanel.maximumSize = Dimension(50, 50) // Set maximum size to prevent expansion
//        iconPanel.border = LineBorder(Color.GRAY, 1)
//        iconPanel.alignmentY = TOP_ALIGNMENT // Align to top
//
//        row1.add(iconPanel)
//
//        val textPanel = JPanel()
//        textPanel.layout = BoxLayout(textPanel, BoxLayout.Y_AXIS)
//        textPanel.add(JLabel("<html>This is the text content<br>which is multilineThis is the text content<br>which is multilineThis is the text content<br>which is multilineThis is the text content<br>which is multilineThis is the text content<br>which is multilineThis is the text content<br>which is multilineThis is the text content<br>which is multilineThis is the text content<br>which is multilineThis is the text content<br>which is multilineThis is the text content<br>which is multilineThis is the text content<br>which is multilineThis is the text content<br>which is multilineThis is the text content<br>which is multilineThis is the text content<br>which is multilineThis is the text content<br>which is multilineThis is the text content<br>which is multilineThis is the text content<br>which is multiline.</html>"))
//        textPanel.alignmentY = TOP_ALIGNMENT // Align to top
//
//        row1.add(textPanel)
//
//        mainPanel.add(row1)
//
//        // Row 2: Image, Res_id, Class
//        val row2 = JPanel()
//        row2.layout = BoxLayout(row2, BoxLayout.X_AXIS)
//
//        val row2Image = JPanel().also {
//            it.preferredSize = Dimension(100, 150)
//            it.border = BorderFactory.createTitledBorder("Image")
//        }
//
//        val row2ResId = JPanel().also {
//            it.layout = BoxLayout(it, BoxLayout.Y_AXIS)
//            it.border = BorderFactory.createTitledBorder("Res_id")
//            it.add(JTextField("-Value - -").also { it.isEditable = false })
//        }
//
//        val row2Class = JPanel().also {
//            it.layout = BoxLayout(it, BoxLayout.Y_AXIS)
//            it.border = BorderFactory.createTitledBorder("Class")
//            it.add(JTextField("- - -Value - - -").also { it.isEditable = false })
//        }
//
//        row2.add(row2Image)
//        row2.add(row2ResId)
//        row2.add(row2Class)
//        mainPanel.add(row2)
//
//        // Row 3: Time Stamp
//        val row3 = JPanel()
//        row3.add(JLabel(SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())).also {
//            it.border = javax.swing.BorderFactory.createTitledBorder("time stamp")
//        })
//        mainPanel.add(row3)
//
//        return mainPanel
//    }
//
//
//
//    private fun createTestUI(): JPanel {
//        val panel = JPanel(GridBagLayout())
//        val gbc = GridBagConstraints()
//        gbc.fill = GridBagConstraints.HORIZONTAL
//        gbc.insets = Insets(5, 5, 5, 5)
//
//        // JTextArea with ScrollPane
//        val textArea = JTextArea("This is a JTextArea This is a JTextArea This is a JTextArea This is a JTextArea This is a JTextArea This is a JTextArea This is a JTextArea This is a JTextArea").apply {
//            background = JBColor.GREEN
//            lineWrap = true
//            wrapStyleWord = true
//            isOpaque = true
//            font = UIManager.getFont("Label.font")
//            foreground = JBColor.BLACK
//            isEditable = false
//        }
//        val scrollPane = JBScrollPane(textArea)
//
//        gbc.gridx = 0
//        gbc.gridy = 0
//        gbc.weightx = 1.0
//        gbc.weighty = 0.0 // JTextArea takes 20% of the height
//        gbc.gridheight = GridBagConstraints.REMAINDER
//        panel.add(scrollPane, gbc)
//
//        // Bottom panel taking remaining space
//        val bottomPanel = getTPanel()
//        bottomPanel.background = JBColor.BLUE
//        bottomPanel.isOpaque = true
//
//        gbc.gridy = 1
//        gbc.weighty = 1.0 // Bottom panel takes 80% of the height
//        gbc.fill = GridBagConstraints.BOTH
//        panel.add(bottomPanel, gbc)
//
//        return panel
//    }
//
//    private fun getTPanel() = panel {
//        row("Test label") {
//
//        }
//    }
//
//
//    private fun createDropdownPanel(): JPanel {
//        val categoryLabel = JLabel("Category:")
//        val categoryComboBox = ComboBox(arrayOf("Option 1", "Option 2", "Option 3"))
//
//        val adbDevicesLabel = JLabel("ADB devices:")
//        devicesComboBox.addActionListener {
//            val selectedDevice = devicesComboBox.selectedItem as? String
//            presenter.setSelectedDevice(selectedDevice)
//        }
//
//        val panel = JPanel(GridBagLayout()).apply {
//            val gbc = GridBagConstraints().apply {
//                fill = GridBagConstraints.HORIZONTAL
//                insets = JBUI.insets(5) // Padding between elements
//                border = JBEmptyBorder(5, 10, 5, 10)
//            }
//
//            // Category Label (does not stretch)
//            gbc.gridx = 0
//            gbc.weightx = 0.05 // Small weight for the label
//            add(categoryLabel, gbc)
//
//            // Category Dropdown (70% space)
//            gbc.gridx = 1
//            gbc.weightx = 0.7
//            add(testCaseComboBox, gbc)
//
//            // Type Label (does not stretch)
//            gbc.gridx = 2
//            gbc.weightx = 0.05
//            add(adbDevicesLabel, gbc)
//
//            // Type Dropdown (30% space)
//            gbc.gridx = 3
//            gbc.weightx = 0.3
//            add(devicesComboBox, gbc)
//        }
//
//        return panel
//    }
//
//    private fun createJBListPanel(): JPanel {
//        var imageLabel: JLabel = JLabel()
//        val jbList = JBList(listModel).apply {
//            selectionMode = ListSelectionModel.SINGLE_SELECTION
//            cellRenderer = ListCellRenderer { list, value, index, isSelected, cellHasFocus -> // Panel for list item
//                val panel = JPanel(BorderLayout()).apply {
//                    border = BorderFactory.createEmptyBorder(5, 10, 5, 10)
//                    background = if (isSelected) list.selectionBackground else list.background
//                }
//
//                val imageIcon = ImageIcon(value.screenshotPath) // Load image dynamically if needed
//                val scaledImage = imageIcon.image.getScaledInstance(180, 400, Image.SCALE_SMOOTH)
//                val scaledIcon = ImageIcon(scaledImage)
//                imageLabel = JLabel().apply {
//                    icon = scaledIcon
//                    isOpaque = true // Ensures it receives mouse events
//                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) // Change cursor on hover
//                }
//
//                imageLabel.addMouseListener(object : MouseAdapter() {
//                    override fun mouseClicked(e: MouseEvent?) {
//                        println("Image clicked!!!!!!")
//                        value.screenshotPath?.let { openImageInIntelliJ(it) }
//                    }
//                })
//
//                // Text Panel
//                val textPanel = JPanel(GridBagLayout()).apply {
//                    layout = BoxLayout(this, BoxLayout.Y_AXIS) // Vertical layout for text + timestamp
//                    background = panel.background
//                }
//
//                // Main Text
//                val textArea = JTextArea(value.aiResponse.context).apply {
//                    lineWrap = true
//                    wrapStyleWord = true
//                    isOpaque = false
//                    font = UIManager.getFont("Label.font")
//                    foreground = JBColor.BLACK
//                    isEditable = false
//                    border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
////                    background = JBColor.GREEN
//                }
//
//                // Timestamp
//                val timestampLabel = JLabel(value.timestamp.toString()).apply {
//                    foreground = JBColor.GRAY
//                    font = font.deriveFont(Font.ITALIC, 10f)
//                    horizontalAlignment = SwingConstants.LEFT
//                    background = JBColor.YELLOW
//                }
//
//                val testPanel = JPanel().apply {
//
//                }
//
//                textPanel.add(textArea)
//                textPanel.add(createStatsTable())
//                textPanel.add(timestampLabel)
//
//                // Layout: Image on Left, Text on Right
//                panel.add(imageLabel, BorderLayout.WEST)
//                panel.add(textPanel, BorderLayout.CENTER)
//
//                panel
//            }
//        }
//
//        val scrollPane = JBScrollPane(jbList)
//
//        val panel = JPanel(BorderLayout()).apply {
//            preferredSize = Dimension(300, 200)
//            add(scrollPane, BorderLayout.CENTER)
//        }
//
//        return panel
//    }
//
//    private fun createPanel() = panel {
//        row {
//            val textArea = JTextArea().apply {
//                rows = 2
//                lineWrap = true
//                wrapStyleWord = true
//                border = JBUI.Borders.empty(5)
//            }
//
//            val sendButton = testSendButton.apply {
//                addActionListener {
//                    when(testRunningStatus) {
//                        TestRunningStatus.RUNNING -> {
//                            println("Already test running, stopping it .... ")
//                            presenter.stopRunningTest()
//                        }
//                        TestRunningStatus.STOPPED -> {
//                            if(textArea.text.isEmpty()) {
//                                println("No text available to process")
//                            }
//                            println("Sending: ${textArea.text}")
//                            listModel.clear()
//                            presenter.runTestCase(TestCase(123, textArea.text))
//                        }
//                    }
//
//                }
//            }
//
//            // Panel to hold text area and button
//            val panel = JPanel(BorderLayout()).apply {
//                border = JBUI.Borders.empty(10, 20)
//                background = JBColor.GREEN
//                isOpaque = true
//                add(JBScrollPane(textArea), BorderLayout.CENTER)
//                add(sendButton, BorderLayout.EAST) // Place button at the right
//            }
//
//            cell(panel)
//                    .align(Align.FILL)
//                    .resizableColumn()
//        }
//    }
//
//    fun createStatsTable(): JBScrollPane {
//        val columnNames = arrayOf("Resource ID", "Action", "Bounds", "Other Info")
//
//        // Sample Data
//        val data = arrayOf(
//                arrayOf("btn_submit", "Click", "[10,20][100,120]", "Enabled"),
//                arrayOf("txt_username", "Type", "[30,40][200,60]", "Required"),
//                arrayOf("img_logo", "None", "[50,70][150,220]", "Visible")
//        )
//
//        // Creating Table Model
//        val tableModel = DefaultTableModel(data, columnNames)
//        val table = JBTable(tableModel)
//
//        // Wrapping table inside scroll pane
//        return JBScrollPane(table)
//    }
//
//
//
//    private fun openImageInIntelliJ(imagePath: String) {
//        val file = File(imagePath)
//        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
//        if (virtualFile != null) {
//            FileEditorManager.getInstance(toolWindow.project).openFile(virtualFile, true)
//        } else {
//            JOptionPane.showMessageDialog(null, "Unable to open image", "Error", JOptionPane.ERROR_MESSAGE)
//        }
//    }
//
//    fun getContentPanel(): JPanel {
//        return contentPanel
//    }
//
//    override fun appendTestStep(actionList: List<AiResponseData>) {
//        val currentSize = listModel.size
//
//        for(i in currentSize until actionList.size) {
//            listModel.addElement(actionList[i])
//        }
//    }
//
//    override fun updateTestStatus(testRunningStatus: TestRunningStatus) {
//        this.testRunningStatus = testRunningStatus
//        when(testRunningStatus) {
//            TestRunningStatus.RUNNING -> {
//                testSendButton.text = "Running"
//                testSendButton.icon = AllIcons.Actions.RunToCursor
//            }
//            TestRunningStatus.STOPPED -> {
//                testSendButton.text = "Send"
//                testSendButton.icon = AllIcons.Duplicates.SendToTheRight
//            }
//        }
//    }
//
//    override fun updateAdbDevices(adbDevices: List<String>) {
//        devicesComboBox.removeAllItems()
//        adbDevices.forEach {
//            devicesComboBox.addItem(it)
//        }
//    }
//}