package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Brokk;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.Context;
import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.Models;
import io.github.jbellis.brokk.Project;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

public class Chrome implements AutoCloseable, IConsoleIO {
    private static final Logger logger = LogManager.getLogger(Chrome.class);

    private final String BGTASK_EMPTY = "No background tasks";

    // Dependencies:
    ContextManager contextManager;

    // Swing components:
    final JFrame frame;
    private MarkdownOutputPanel llmStreamArea;
    private JTextArea systemArea;
    private JLabel commandResultLabel;
    private JTextArea commandInputField;
    private JLabel backgroundStatusLabel;
    private JScrollPane systemScrollPane;

    // Context History Panel
    private JTable contextHistoryTable;
    private DefaultTableModel contextHistoryModel;

    // Track the horizontal split that holds the history panel
    private JSplitPane historySplitPane;
    private JSplitPane verticalSplitPane;
    private JSplitPane contextGitSplitPane;

    // Capture panel buttons
    private JButton captureTextButton;
    private JButton editReferencesButton;

    // Panels:
    private ContextPanel contextPanel;
    private GitPanel gitPanel;

    // Buttons for the command input panel:
    private JButton codeButton;  // renamed from goButton
    private JButton askButton;
    private JButton searchButton;
    private JButton runButton;
    private JButton stopButton;  // cancels the current user-driven task

    // Track the currently running user-driven future (Code/Ask/Search/Run)
    volatile Future<?> currentUserTask;
    private JScrollPane llmScrollPane;
    JTextArea captureDescriptionArea;

    private Project getProject() {
        return contextManager == null ? null : contextManager.getProject();
    }

    /**
     * Enum representing the different types of context actions that can be performed.
     * This replaces the use of magic strings when calling performContextActionAsync.
     */
    public enum ContextAction {
        EDIT, READ, SUMMARIZE, DROP, COPY, PASTE
    }

    /**
     * Default constructor sets up the UI.
     * We call this from Brokk after creating contextManager, before creating the Coder,
     * and before calling .resolveCircularReferences(...).
     * We allow contextManager to be null for the initial empty UI.
     */
    public Chrome(ContextManager contextManager) {
        this.contextManager = contextManager;

        // 1) Set FlatLaf Look & Feel - we'll use light as default initially
        // The correct theme will be applied in onComplete() when project is available
        try {
            com.formdev.flatlaf.FlatLightLaf.setup();
        } catch (Exception e) {
            logger.warn("Failed to set LAF, using default", e);
        }

        // 2) Build main window
        frame = new JFrame("Brokk: Code Intelligence for AI");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(800, 1200);  // Taller than wide
        frame.setLayout(new BorderLayout());

        // Set application icon
        try {
            var iconUrl = getClass().getResource(Brokk.ICON_RESOURCE);
            if (iconUrl != null) {
                var icon = new ImageIcon(iconUrl);
                frame.setIconImage(icon.getImage());
            } else {
                logger.warn("Could not find resource {}", Brokk.ICON_RESOURCE);
            }
        } catch (Exception e) {
            logger.warn("Failed to set application icon", e);
        }

        // 3) Main panel (top area + bottom area)
        frame.add(buildMainPanel(), BorderLayout.CENTER);

        // 4) Build menu
        frame.setJMenuBar(MenuBar.buildMenuBar(this));

        // 5) Register global keyboard shortcuts
        registerGlobalKeyboardShortcuts();

        if (contextManager == null) {
            disableUserActionButtons();
            disableContextActionButtons();
        }
    }

    public void onComplete() {
        assert contextManager != null;

        // Load saved theme, window size, and position
        frame.setTitle("Brokk: " + getProject().getRoot());
        initializeThemeManager();
        loadWindowSizeAndPosition();

        // populate the commit panel
        updateCommitPanel();

        // show the window
        frame.setVisible(true);
        // this gets it to respect the minimum size on buttons panel, fuck it
        frame.validate();
        frame.repaint();

        // Set focus to command input field on startup
        commandInputField.requestFocusInWindow();
    }

    private void initializeThemeManager() {
        assert getProject() != null;

        logger.debug("Initializing theme manager");
        // Initialize theme manager now that all components are created
        // and conmtextManager should be properly set
        themeManager = new GuiTheme(getProject(), frame, llmScrollPane, this);

        // Apply current theme based on project settings
        String currentTheme = getProject().getTheme();
        logger.debug("Applying theme from project settings: {}", currentTheme);
        // Apply the theme from project settings now
        boolean isDark = THEME_DARK.equalsIgnoreCase(currentTheme);
        themeManager.applyTheme(isDark);
        llmStreamArea.updateTheme(isDark);
    }

    /**
     * Build the main panel that includes:
     * - the LLM stream (top)
     * - the command result label
     * - the command input
     * - the context panel
     * - the background status label at bottom
     */
    private JPanel buildMainPanel() {
        var panel = new JPanel(new BorderLayout());

        var contentPanel = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        gbc.gridx = 0;
        gbc.insets = new Insets(2, 2, 2, 2);

        var rightPanel = new JPanel(new BorderLayout());

        // LLM streaming area 
        llmScrollPane = buildLLMStreamScrollPane();
        var outputPanel = new JPanel(new BorderLayout());
        outputPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Output",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));
        outputPanel.add(llmScrollPane, BorderLayout.CENTER);

        // Add capture output panel in the middle
        var capturePanel = buildCaptureOutputPanel();
        outputPanel.add(capturePanel, BorderLayout.SOUTH);

        // Add system messages area at the bottom
        systemScrollPane = buildSystemMessagesArea();
        rightPanel.add(outputPanel, BorderLayout.CENTER);
        rightPanel.add(systemScrollPane, BorderLayout.SOUTH);

        // Build the history panel, but don't add it to the split pane yet
        // We'll do this after we know the button size
        var contextHistoryPanel = buildContextHistoryPanel();

        // Store this horizontal split in our class field
        this.historySplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        historySplitPane.setLeftComponent(contextHistoryPanel);
        historySplitPane.setRightComponent(rightPanel);
        historySplitPane.setResizeWeight(0.2); // 80% to output, 20% to history

        // Create a split pane with output+history in top and command+context+status in bottom
        verticalSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        verticalSplitPane.setTopComponent(historySplitPane);

        // Create a panel for everything below the output area
        var bottomPanel = new JPanel(new BorderLayout());

        // We will size the history panel after the frame is actually displayed
        SwingUtilities.invokeLater(this::setInitialHistoryPanelWidth);

        // Create a top panel for the result label and command input
        var topControlsPanel = new JPanel(new BorderLayout(0, 2));

        // 2. Command result label
        var resultLabel = buildCommandResultLabel();
        topControlsPanel.add(resultLabel, BorderLayout.NORTH);

        // 3. Command input with prompt
        var commandPanel = buildCommandInputPanel();
        topControlsPanel.add(commandPanel, BorderLayout.SOUTH);

        // Add the top controls to the top of the bottom panel
        bottomPanel.add(topControlsPanel, BorderLayout.NORTH);

        // 4. Create a vertical split pane to hold the context panel and git panel
        // 4a. Context panel (with border title) at the top
        contextPanel = new ContextPanel(this, contextManager);
        this.contextGitSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        contextGitSplitPane.setTopComponent(contextPanel);

        // 4b. Git panel at the bottom
        gitPanel = new GitPanel(this, contextManager);
        contextGitSplitPane.setBottomComponent(gitPanel);

        // Set resize weight so context panel gets extra space
        contextGitSplitPane.setResizeWeight(0.7); // 70% to context, 30% to git

        // Add the split pane to the bottom panel
        bottomPanel.add(contextGitSplitPane, BorderLayout.CENTER);

        // 5. Background status label at the very bottom
        var statusLabel = buildBackgroundStatusLabel();
        bottomPanel.add(statusLabel, BorderLayout.SOUTH);

        verticalSplitPane.setBottomComponent(bottomPanel);

        // Add the vertical split pane to the content panel
        gbc.weighty = 1.0;
        gbc.gridy = 0;
        contentPanel.add(verticalSplitPane, gbc);

        panel.add(contentPanel, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Builds the Context History panel that shows past contexts
     */
    private JPanel buildContextHistoryPanel() {
        // Create history panel
        var panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Context History",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));

        // Create table model with columns - first two columns are visible, third is hidden
        contextHistoryModel = new DefaultTableModel(
                new Object[]{"", "Action", "Context"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        contextHistoryTable = new JTable(contextHistoryModel);
        contextHistoryTable.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
        contextHistoryTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Remove table header
        contextHistoryTable.setTableHeader(null);
        
        // Set up tooltip renderer for description column (index 1)
        contextHistoryTable.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                          boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel label = (JLabel)super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column);
                
                // Set the tooltip to show the full text
                if (value != null) {
                    label.setToolTipText(value.toString());
                }
                
                return label;
            }
        });

        // Set up emoji renderer for first column
        contextHistoryTable.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                          boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel label = (JLabel)super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column);
                
                // Center-align the emoji
                label.setHorizontalAlignment(JLabel.CENTER);
                
                return label;
            }
        });

        // Add selection listener to preview context
        contextHistoryTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = contextHistoryTable.getSelectedRow();
                if (row >= 0 && row < contextManager.getContextHistory().size()) {
                    var ctx = contextManager.getContextHistory().get(row);
                    loadContext(ctx);
                }
            }
        });

        // Add right-click context menu for history operations
        contextHistoryTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextHistoryPopupMenu(e);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextHistoryPopupMenu(e);
                }
            }
        });

        // Adjust column widths - set emoji column width and hide the context object column
        contextHistoryTable.getColumnModel().getColumn(0).setPreferredWidth(30);
        contextHistoryTable.getColumnModel().getColumn(0).setMinWidth(30);
        contextHistoryTable.getColumnModel().getColumn(0).setMaxWidth(30);
        contextHistoryTable.getColumnModel().getColumn(1).setPreferredWidth(150);
        contextHistoryTable.getColumnModel().getColumn(2).setMinWidth(0);
        contextHistoryTable.getColumnModel().getColumn(2).setMaxWidth(0);
        contextHistoryTable.getColumnModel().getColumn(2).setWidth(0);

        // Add table to scroll pane with SmartScroll
        var scrollPane = new JScrollPane(contextHistoryTable);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        new SmartScroll(scrollPane);

        // Add undo/redo buttons at the bottom
        var buttonPanel = new JPanel(new GridLayout(2, 1, 0, 5));
        buttonPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

        var undoButton = new JButton("Undo");
        undoButton.setMnemonic(KeyEvent.VK_Z);
        undoButton.setToolTipText("Undo the most recent history entry");
        undoButton.addActionListener(e -> {
            disableUserActionButtons();
            disableContextActionButtons();
            currentUserTask = contextManager.undoContextAsync();
        });

        var redoButton = new JButton("Redo");
        redoButton.setMnemonic(KeyEvent.VK_Y);
        redoButton.setToolTipText("Redo the most recently undone entry");
        redoButton.addActionListener(e -> {
            disableUserActionButtons();
            disableContextActionButtons();
            currentUserTask = contextManager.redoContextAsync();
        });

        buttonPanel.add(undoButton);
        buttonPanel.add(redoButton);

        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

    /**
     * Lightweight method to preview a context without updating history
     * Only updates the LLM text area and context panel display
     */
    public void loadContext(Context ctx) {
        assert ctx != null;

        SwingUtilities.invokeLater(() -> {
            // Don't clear history selection when previewing
            contextPanel.populateContextTable(ctx);

            // If there's textarea content, restore it to the LLM output area
            llmStreamArea.setText(ctx.getParsedOutput() == null ? "" : ctx.getParsedOutput().output());

            // Scroll to the top
            SwingUtilities.invokeLater(() -> {
                llmScrollPane.getVerticalScrollBar().setValue(0);
            });

            updateCaptureButtons(ctx);
        });
    }

    /**
     * Shows the context menu for the context history table
     */
    // Theme manager
    GuiTheme themeManager;

    // Theme constants - matching GuiTheme values
    private static final String THEME_DARK = "dark";
    private static final String THEME_LIGHT = "light";

    /**
     * Switches between light and dark theme
     *
     * @param isDark true for dark theme, false for light theme
     */
    public void switchTheme(boolean isDark) {
        themeManager.applyTheme(isDark);

        // And the output
        llmStreamArea.updateTheme(isDark);

        // Update themes in all preview windows (if there are open ones)
        for (Window window : Window.getWindows()) {
            if (window instanceof JFrame && window != frame) {
                Container contentPane = ((JFrame) window).getContentPane();
                if (contentPane instanceof PreviewPanel) {
                    ((PreviewPanel) contentPane).updateTheme(themeManager);
                }
            }
        }
    }

    private void showContextHistoryPopupMenu(MouseEvent e) {
        int row = contextHistoryTable.rowAtPoint(e.getPoint());
        if (row < 0) return;

        // Select the row under the cursor
        contextHistoryTable.setRowSelectionInterval(row, row);

        // Create popup menu
        JPopupMenu popup = new JPopupMenu();
        JMenuItem undoToHereItem = new JMenuItem("Undo to here");
        undoToHereItem.addActionListener(event -> restoreContextFromHistory(row));
        popup.add(undoToHereItem);

        // Register popup with theme manager
        if (themeManager != null) {
            themeManager.registerPopupMenu(popup);
        }

        // Show popup menu
        popup.show(contextHistoryTable, e.getX(), e.getY());
    }

    /**
     * Restore context to a specific point in history
     */
    private void restoreContextFromHistory(int index) {
        int currentIndex = contextManager.getContextHistory().size() - 1;
        if (index < currentIndex) {
            disableUserActionButtons();
            disableContextActionButtons();
            int stepsToUndo = currentIndex - index;
            currentUserTask = contextManager.undoContextAsync(stepsToUndo);
        }
    }

    /**
     * Gets the current text from the LLM output area
     */
    public String getLlmOutputText() {
        return SwingUtil.runOnEDT(() -> llmStreamArea.getText(), null);
    }

    private JScrollPane buildLLMStreamScrollPane() {
        llmStreamArea = new MarkdownOutputPanel();

        // Wrap it in a scroll pane so it can scroll if content is large
        var jsp = new JScrollPane(llmStreamArea);
        jsp.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        jsp.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        new SmartScroll(jsp);

        // Add a text change listener to update capture buttons
        llmStreamArea.addTextChangeListener(() -> updateCaptureButtons(null));

        return jsp;
    }

    /**
     * Builds the system messages area that appears below the LLM output area.
     */
    private JScrollPane buildSystemMessagesArea() {
        // Create text area for system messages
        systemArea = new JTextArea();
        systemArea.setEditable(false);
        systemArea.setLineWrap(true);
        systemArea.setWrapStyleWord(true);
        systemArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        systemArea.setRows(3);

        // Create scroll pane with border and title
        systemScrollPane = new JScrollPane(systemArea);
        systemScrollPane.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "System Messages",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));
        new SmartScroll(systemScrollPane);

        return systemScrollPane;
    }

    /**
     * Creates the command result label used to display messages.
     */
    private JComponent buildCommandResultLabel() {
        commandResultLabel = new JLabel(" ");
        commandResultLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        commandResultLabel.setBorder(new EmptyBorder(5, 8, 5, 8));
        return commandResultLabel;
    }

    /**
     * Creates the bottom-most background status label that shows "Working on: ..." or is blank when idle.
     */
    private JComponent buildBackgroundStatusLabel() {
        backgroundStatusLabel = new JLabel(BGTASK_EMPTY);
        backgroundStatusLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        backgroundStatusLabel.setBorder(new EmptyBorder(2, 5, 2, 5));
        return backgroundStatusLabel;
    }

    /**
     * Creates a panel with a single-line text field for commands, plus “Go / Ask / Search” on the left and a “Stop” button on the right.
     * The panel is titled "Instructions".
     */
    private JPanel buildCommandInputPanel() {
        var wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                        BorderFactory.createEtchedBorder(),
                        "Instructions",
                        javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                        javax.swing.border.TitledBorder.DEFAULT_POSITION,
                        new Font(Font.DIALOG, Font.BOLD, 12)
                ),
                new EmptyBorder(5, 5, 5, 5)
        ));

        // Add history dropdown at the top of the wrapper
        JPanel historyPanel = buildHistoryDropdown();
        wrapper.add(historyPanel, BorderLayout.NORTH);

        commandInputField = new JTextArea(3, 40);
        commandInputField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        commandInputField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY),
                BorderFactory.createEmptyBorder(2, 5, 2, 5)
        ));
        commandInputField.setLineWrap(true);
        commandInputField.setWrapStyleWord(true);
        commandInputField.setRows(3);
        commandInputField.setMinimumSize(new Dimension(100, 80));

        // Create a JScrollPane for the text area
        JScrollPane commandScrollPane = new JScrollPane(commandInputField);
        commandScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        commandScrollPane.setPreferredSize(new Dimension(600, 80)); // Set preferred height for 3 lines
        commandScrollPane.setMinimumSize(new Dimension(100, 80));

        // Emacs-like keybindings
        wrapper.add(commandScrollPane, BorderLayout.CENTER);

        // Left side: code/ask/search/run
        var leftButtonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        codeButton = new JButton("Code");
        codeButton.setMnemonic(KeyEvent.VK_C);
        codeButton.setToolTipText("Tell the LLM to write code to solve this problem using the current context");
        codeButton.addActionListener(e -> runCodeCommand());

        askButton = new JButton("Ask");
        askButton.setMnemonic(KeyEvent.VK_A);
        askButton.setToolTipText("Ask the LLM a question about the current context");
        askButton.addActionListener(e -> runAskCommand());

        searchButton = new JButton("Search");
        searchButton.setMnemonic(KeyEvent.VK_S);
        searchButton.setToolTipText("Explore the codebase to find answers that are NOT in the current context");
        searchButton.addActionListener(e -> runSearchCommand());

        runButton = new JButton("Run in Shell");
        runButton.setMnemonic(KeyEvent.VK_N);
        runButton.setToolTipText("Execute the current instructions as a shell command");
        runButton.addActionListener(e -> runRunCommand());

        leftButtonsPanel.add(codeButton);
        leftButtonsPanel.add(askButton);
        leftButtonsPanel.add(searchButton);
        leftButtonsPanel.add(runButton);

        // Right side: stop
        var rightButtonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        stopButton = new JButton("Stop");
        stopButton.setToolTipText("Cancel the current operation");
        stopButton.addActionListener(e -> stopCurrentUserTask());
        rightButtonsPanel.add(stopButton);

        // We'll place left and right panels in a horizontal layout
        var buttonsHolder = new JPanel(new BorderLayout());
        buttonsHolder.add(leftButtonsPanel, BorderLayout.WEST);
        buttonsHolder.add(rightButtonsPanel, BorderLayout.EAST);

        // Add text area at top, buttons panel below
        wrapper.add(commandScrollPane, BorderLayout.CENTER);
        wrapper.add(buttonsHolder, BorderLayout.SOUTH);

        // Set "Go" as default
        frame.getRootPane().setDefaultButton(codeButton);

        return wrapper;
    }

    /**
     * Invoked on "Code" button or pressing Enter in the command input field.
     */
    private void runCodeCommand() {
        var input = commandInputField.getText();
        if (input.isBlank()) {
            toolErrorRaw("Please enter a command or text");
            return;
        }

        // Check if LLM is available
        if (!contextManager.getCoder().isLlmAvailable()) {
            toolError("No LLM available (missing API keys)");
            return;
        }

        // Add to text history
        getProject().addToTextHistory(input, 20);

        llmStreamArea.setText("# Code\n" + commandInputField.getText() + "\n\n# Response\n");
        commandInputField.setText("");

        disableUserActionButtons();
        // schedule in ContextManager
        currentUserTask = contextManager.runCodeCommandAsync(input);
    }

    /**
     * Invoked on "Run" button
     */
    private void runRunCommand() {
        var input = commandInputField.getText();
        if (input.isBlank()) {
            toolError("Please enter a command to run");
        }

        // Add to text history
        getProject().addToTextHistory(input, 20);

        llmStreamArea.setText("# Run\n" + commandInputField.getText() + "\n\n# Output\n");
        commandInputField.setText("");

        disableUserActionButtons();
        currentUserTask = contextManager.runRunCommandAsync(input);
    }

    public String getInputText() {
        return SwingUtil.runOnEDT(() -> commandInputField.getText(), "");
    }

    /**
     * Invoked on "Ask" button
     */
    private void runAskCommand() {
        var input = commandInputField.getText();
        if (input.isBlank()) {
            toolErrorRaw("Please enter a question");
            return;
        }

        // Check if LLM is available
        if (!contextManager.getCoder().isLlmAvailable()) {
            toolError("No LLM available (missing API keys)");
            return;
        }

        // Add to text history
        getProject().addToTextHistory(input, 20);

        llmStreamArea.setText("# Ask\n" + commandInputField.getText() + "\n\n# Response\n");
        commandInputField.setText("");

        disableUserActionButtons();
        currentUserTask = contextManager.runAskAsync(input);
    }

    /**
     * Invoked on "Search" button
     */
    private void runSearchCommand() {
        var input = commandInputField.getText();
        if (input.isBlank()) {
            toolErrorRaw("Please provide a search query");
            return;
        }

        // Check if LLM is available
        if (!contextManager.getCoder().isLlmAvailable()) {
            toolError("No LLM available (missing API keys)");
            return;
        }

        // Add to text history
        getProject().addToTextHistory(input, 20);

        llmStreamArea.setText("# Search\n" + commandInputField.getText() + "\n\n");
        llmStreamArea.append("# Please be patient\n\nBrokk makes multiple requests to the LLM while searching. Progress is logged in System Messages below.");
        commandInputField.setText("");

        disableUserActionButtons();
        currentUserTask = contextManager.runSearchAsync(input);
    }

    @Override
    public void clear() {
        SwingUtilities.invokeLater(() -> {
            llmStreamArea.clear();
        });
    }

    /**
     * Disables "Go/Ask/Search" to prevent overlapping tasks, until re-enabled
     */
    void disableUserActionButtons() {
        SwingUtilities.invokeLater(() -> {
            codeButton.setEnabled(false);
            askButton.setEnabled(false);
            searchButton.setEnabled(false);
            runButton.setEnabled(false);
            stopButton.setEnabled(true);
        });
    }

    /**
     * Re-enables "Go/Ask/Search" when the task completes or is canceled
     */
    public void enableUserActionButtons() {
        SwingUtilities.invokeLater(() -> {
            codeButton.setEnabled(true);
            askButton.setEnabled(true);
            searchButton.setEnabled(true);
            runButton.setEnabled(true);
            stopButton.setEnabled(false);
            updateCommitPanel();
        });
    }

    /**
     * Disables the context action buttons while an action is in progress
     */
    void disableContextActionButtons() {
        // No longer needed - buttons are in menus now
    }

    /**
     * Re-enables context action buttons
     */
    public void enableContextActionButtons() {
        // No longer needed - buttons are in menus now
        contextPanel.updateContextActions();
    }

    /**
     * Cancels the currently running user-driven Future (Go/Ask/Search), if any
     */
    private void stopCurrentUserTask() {
        if (currentUserTask != null && !currentUserTask.isDone()) {
            currentUserTask.cancel(true);
        }
    }
    
    /**
     * Updates the uncommitted files table and the state of the suggest commit button
     */
    public void updateCommitPanel() {
        gitPanel.updateCommitPanel();
    }

    /**
     * Registers global keyboard shortcuts for undo/redo
     */
    private void registerGlobalKeyboardShortcuts() {
        var rootPane = frame.getRootPane();

        // Ctrl+Z => undo
        var undoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(undoKeyStroke, "globalUndo");
        rootPane.getActionMap().put("globalUndo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                disableUserActionButtons();
                disableContextActionButtons();
                currentUserTask = contextManager.undoContextAsync();
            }
        });

        // Ctrl+Shift+Z => redo
        var redoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                                                   InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(redoKeyStroke, "globalRedo");
        rootPane.getActionMap().put("globalRedo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                disableUserActionButtons();
                disableContextActionButtons();
                currentUserTask = contextManager.redoContextAsync();
            }
        });

        // Ctrl+V => paste
        var pasteKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK);
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(pasteKeyStroke, "globalPaste");
        rootPane.getActionMap().put("globalPaste", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                currentUserTask = contextManager.performContextActionAsync(ContextAction.PASTE, List.of());
            }
        });
    }

    /**
     * Shows a dialog for editing LLM API secret keys.
     */
    void showSecretKeysDialog() {
        if (getProject() == null) {
            toolErrorRaw("Project not available");
            return;
        }

        // Reuse the existing code for editing keys
        // unchanged from original example
        JDialog dialog = new JDialog(frame, "Edit LLM API Keys", true);
        dialog.setLayout(new BorderLayout());

        // main panel
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        var existingKeys = getProject().getLlmKeys();
        List<KeyValueRowPanel> keyRows = new ArrayList<>();

        // if empty, add one row
        if (existingKeys.isEmpty()) {
            var row = new KeyValueRowPanel(Models.defaultKeyNames);
            keyRows.add(row);
            mainPanel.add(row);
        }

        // Actually, we want multiple rows in a vertical layout
        var keysPanel = new JPanel();
        keysPanel.setLayout(new BoxLayout(keysPanel, BoxLayout.Y_AXIS));

        if (!existingKeys.isEmpty()) {
            for (var entry : existingKeys.entrySet()) {
                var row = new KeyValueRowPanel(Models.defaultKeyNames, entry.getKey(), entry.getValue());
                keyRows.add(row);
                keysPanel.add(row);
            }
        } else {
            var row = new KeyValueRowPanel(Models.defaultKeyNames, "", "");
            keyRows.add(row);
            keysPanel.add(row);
        }

        var scrollPane = new JScrollPane(keysPanel);
        scrollPane.setPreferredSize(new Dimension((int) (frame.getWidth() * 0.9), 250));
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // Add/Remove
        var addRemovePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        var addButton = new JButton("Add Key");
        addButton.addActionListener(ev -> {
            var newRow = new KeyValueRowPanel(Models.defaultKeyNames);
            keyRows.add(newRow);
            keysPanel.add(newRow);
            keysPanel.revalidate();
            keysPanel.repaint();
        });

        var removeButton = new JButton("Remove Last Key");
        removeButton.addActionListener(ev -> {
            if (!keyRows.isEmpty()) {
                var last = keyRows.remove(keyRows.size() - 1);
                keysPanel.remove(last);
                keysPanel.revalidate();
                keysPanel.repaint();
            }
        });
        addRemovePanel.add(addButton);
        addRemovePanel.add(removeButton);
        mainPanel.add(addRemovePanel, BorderLayout.NORTH);

        // OK/Cancel
        var actionButtonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var okButton = new JButton("OK");
        var cancelButton = new JButton("Cancel");

        okButton.addActionListener(ev -> {
            var newKeys = new java.util.HashMap<String, String>();
            boolean hasEmptyKey = false;

            for (var row : keyRows) {
                var key = row.getKeyName();
                var value = row.getKeyValue();
                if (key.isBlank() && value.isBlank()) {
                    continue;
                }
                if (key.isBlank()) {
                    hasEmptyKey = true;
                    continue;
                }
                newKeys.put(key, value);
            }

            if (hasEmptyKey) {
                JOptionPane.showMessageDialog(dialog,
                                              "Some keys have empty names and will be skipped.",
                                              "Warning",
                                              JOptionPane.WARNING_MESSAGE);
            }

            getProject().saveLlmKeys(newKeys);
            systemOutput("Saved " + newKeys.size() + " API keys");
            dialog.dispose();
        });

        cancelButton.addActionListener(ev -> dialog.dispose());

        actionButtonsPanel.add(okButton);
        actionButtonsPanel.add(cancelButton);
        mainPanel.add(actionButtonsPanel, BorderLayout.SOUTH);

        dialog.add(mainPanel);
        dialog.getRootPane().setDefaultButton(okButton);
        dialog.getRootPane().registerKeyboardAction(
                event -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );
        dialog.pack();
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    /**
     * For the IConsoleIO interface, sets the text in commandResultLabel. Safe to call from any thread.
     */
    @Override
    public void actionOutput(String msg) {
        SwingUtilities.invokeLater(() -> {
            commandResultLabel.setText(msg);
            logger.info(msg);
        });
    }

    @Override
    public void actionComplete() {
        SwingUtilities.invokeLater(() -> commandResultLabel.setText(""));
    }

    @Override
    public void toolErrorRaw(String msg) {
        systemOutput(msg);
    }

    @Override
    public void llmOutput(String token) {
        SwingUtilities.invokeLater(() -> {
            llmStreamArea.append(token);
        });
    }

    @Override
    public void systemOutput(String message) {
        SwingUtilities.invokeLater(() -> {
            // Format timestamp as HH:MM
            String timestamp = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));

            // Add newline if needed
            if (!systemArea.getText().isEmpty() && !systemArea.getText().endsWith("\n")) {
                systemArea.append("\n");
            }

            // Append timestamped message
            systemArea.append(timestamp + ": " + message);
        });
    }

    public void backgroundOutput(String message) {
        SwingUtilities.invokeLater(() -> backgroundStatusLabel.setText(message));
    }

    /**
     * Repopulate the unified context table from ContextManager's history.
     * 
     * Called when we add a new Context or undo/redo to a different one.
     * 
     * NOT called when we select an earlier context.
     */
    public void onContextHistoryChanged() {
        SwingUtilities.invokeLater(() -> {
            updateContextHistoryTable(contextManager.getContextHistory().size() - 1);
        });
    }
    
    @Override
    public void close() {
        logger.info("Closing Chrome UI");
        if (contextManager != null) {
            contextManager.shutdown();
        }
        if (frame != null) {
            frame.dispose();
        }
    }


    /**
     * Opens a preview window for a context fragment
     *
     * @param fragment   The fragment to preview
     * @param syntaxType The syntax highlighting style to use
     */
    public void openFragmentPreview(ContextFragment fragment, String syntaxType) {
        // Hardcode reading the text from the fragment (handles IO errors)
        String content;
        try {
            content = fragment.text();
        } catch (IOException e) {
            systemOutput("Error reading fragment: " + e.getMessage());
            return;
        }

        // Create a JFrame to hold the new PreviewPanel
        var frame = new JFrame("Preview: " + fragment.shortDescription());
        // Pass the theme manager to properly style the preview
        var previewPanel = new PreviewPanel(content, syntaxType, themeManager);
        frame.setContentPane(previewPanel);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        // Load location/dimensions from Project (if any)
        var project = contextManager.getProject();
        var storedBounds = project.getPreviewWindowBounds();
        if (storedBounds != null) {
            frame.setBounds(storedBounds);
        } else {
            frame.setSize(800, 600);
            frame.setLocationRelativeTo(this.getFrame());
        }

        // When the user moves or resizes, store in Project
        frame.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentMoved(java.awt.event.ComponentEvent e) {
                project.savePreviewWindowBounds(frame);
            }

            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                project.savePreviewWindowBounds(frame);
            }
        });

        frame.setVisible(true);
    }

    /**
     * Determines the appropriate syntax style for a fragment
     */
    private String getSyntaxStyleForFragment(ContextFragment fragment) {
        // Default to Java
        var style = SyntaxConstants.SYNTAX_STYLE_JAVA;

        // Check fragment path if it's a file
        if (fragment instanceof ContextFragment.RepoPathFragment) {
            var path = ((ContextFragment.RepoPathFragment) fragment).file().getFileName().toLowerCase();

            if (path.endsWith(".md") || path.endsWith(".markdown")) {
                style = SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
            } else if (path.endsWith(".py")) {
                style = SyntaxConstants.SYNTAX_STYLE_PYTHON;
            } else if (path.endsWith(".js")) {
                style = SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
            } else if (path.endsWith(".html") || path.endsWith(".htm")) {
                style = SyntaxConstants.SYNTAX_STYLE_HTML;
            } else if (path.endsWith(".xml")) {
                style = SyntaxConstants.SYNTAX_STYLE_XML;
            } else if (path.endsWith(".json")) {
                style = SyntaxConstants.SYNTAX_STYLE_JSON;
            } else if (path.endsWith(".css")) {
                style = SyntaxConstants.SYNTAX_STYLE_CSS;
            } else if (path.endsWith(".sql")) {
                style = SyntaxConstants.SYNTAX_STYLE_SQL;
            } else if (path.endsWith(".sh") || path.endsWith(".bash")) {
                style = SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL;
            } else if (path.endsWith(".c") || path.endsWith(".h")) {
                style = SyntaxConstants.SYNTAX_STYLE_C;
            } else if (path.endsWith(".cpp") || path.endsWith(".hpp") ||
                    path.endsWith(".cc") || path.endsWith(".hh")) {
                style = SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS;
            } else if (path.endsWith(".properties")) {
                style = SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE;
            } else if (path.endsWith(".kt")) {
                style = SyntaxConstants.SYNTAX_STYLE_KOTLIN;
            }
        }

        return style;
    }

    /**
     * Loads window size and position from project properties
     */
    private void loadWindowSizeAndPosition() {
        Rectangle bounds = getProject() == null ? null : getProject().getMainWindowBounds();

        // Only apply saved values if they're valid
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            // If no valid size is saved, center the window
            frame.setLocationRelativeTo(null);
            return;
        }

        frame.setSize(bounds.width, bounds.height);
        // Only use the position if it was actually set (not -1)
        if (bounds.x >= 0 && bounds.y >= 0 && isPositionOnScreen(bounds.x, bounds.y)) {
            frame.setLocation(bounds.x, bounds.y);
        } else {
            // If not on a visible screen, center the window
            frame.setLocationRelativeTo(null);
        }

        // Restore split pane positions after the frame has been shown and sized
        SwingUtilities.invokeLater(() -> {
            // Restore vertical split pane position
            int verticalPos = getProject().getVerticalSplitPosition();
            if (verticalPos > 0) {
                verticalSplitPane.setDividerLocation(verticalPos);
            }

            // Restore history split pane position
            int historyPos = getProject().getHistorySplitPosition();
            if (historyPos > 0) {
                historySplitPane.setDividerLocation(historyPos);
            } else {
                // If no saved position, use the previous calculation
                setInitialHistoryPanelWidth();
            }

            // Restore context/git split pane position
            int contextGitPos = getProject().getContextGitSplitPosition();
            if (contextGitPos > 0) {
                contextGitSplitPane.setDividerLocation(contextGitPos);
            }

            // Add listener to save window size and position when they change
            frame.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    getProject().saveMainWindowBounds(frame);
                }

                @Override
                public void componentMoved(java.awt.event.ComponentEvent e) {
                    getProject().saveMainWindowBounds(frame);
                }
            });

            // Add listeners to save split pane positions when they change
            historySplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
                getProject().saveHistorySplitPosition(historySplitPane.getDividerLocation());
            });

            verticalSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
                getProject().saveVerticalSplitPosition(verticalSplitPane.getDividerLocation());
            });

            contextGitSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
                getProject().saveContextGitSplitPosition(contextGitSplitPane.getDividerLocation());
            });
        });
    }

    public void updateContextHistoryTable() {
        int selectedRow = contextHistoryTable.getSelectedRow();
        updateContextHistoryTable(selectedRow);
    }

    /**
     * Updates the context history table with the current context history
     */
    public void updateContextHistoryTable(int selectedRow) {
        SwingUtilities.invokeLater(() -> {
            contextHistoryModel.setRowCount(0);

            // Add rows for each context in history
            for (var ctx : contextManager.getContextHistory()) {
                // Add emoji for AI responses, empty for user actions
                String emoji = (ctx.getParsedOutput() != null) ? "🤖" : "";
                contextHistoryModel.addRow(new Object[]{
                        emoji,
                        ctx.getAction(),
                        ctx // We store the actual context object in hidden column
                });
            }

            // set the selected row
            if (selectedRow >= 0) {
                contextHistoryTable.setRowSelectionInterval(selectedRow, selectedRow);
                contextHistoryTable.scrollRectToVisible(contextHistoryTable.getCellRect(selectedRow, 0, true));
            }

            // Update row heights based on content
            for (int row = 0; row < contextHistoryTable.getRowCount(); row++) {
                adjustRowHeight(row);
            }
        });
    }

    /**
     * Adjusts the height of a row based on its content
     */
    private void adjustRowHeight(int row) {
        if (row >= contextHistoryTable.getRowCount()) return;

        // Get the cell renderer component for the visible column
        var renderer = contextHistoryTable.getCellRenderer(row, 0);
        var comp = contextHistoryTable.prepareRenderer(renderer, row, 0);

        // Calculate the preferred height
        int preferredHeight = comp.getPreferredSize().height;
        preferredHeight = Math.max(preferredHeight, 20); // Minimum height

        // Set the row height if it differs from current height
        if (contextHistoryTable.getRowHeight(row) != preferredHeight) {
            contextHistoryTable.setRowHeight(row, preferredHeight);
        }
    }
    
    /**
     * Gets the context history table for selection checks
     */
    public JTable getContextHistoryTable() {
        assert SwingUtilities.isEventDispatchThread() : "Not on EDT";
        return contextHistoryTable;
    }

    /**
     * Checks if a position is on any available screen
     */
    private boolean isPositionOnScreen(int x, int y) {
        for (var screen : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            for (var config : screen.getConfigurations()) {
                if (config.getBounds().contains(x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Builds the "Capture Output" panel with a horizontal layout:
     * [References Label] [Capture Text] [Edit References]
     */
    private JPanel buildCaptureOutputPanel() {
        var panel = new JPanel(new BorderLayout(5, 3));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));

        // References label in center - will get all extra space
        captureDescriptionArea = new JTextArea("No references found");
        captureDescriptionArea.setEditable(false);
        captureDescriptionArea.setBackground(panel.getBackground());
        captureDescriptionArea.setBorder(null);
        captureDescriptionArea.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
        captureDescriptionArea.setLineWrap(true);
        captureDescriptionArea.setWrapStyleWord(true);
        panel.add(captureDescriptionArea, BorderLayout.CENTER);

        // Buttons panel on the right
        var buttonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));

        // "Capture Text" button
        captureTextButton = new JButton("Capture Text");
        captureTextButton.setMnemonic(KeyEvent.VK_T);
        captureTextButton.setToolTipText("Capture the output as context");
        captureTextButton.addActionListener(e -> {
            contextManager.captureTextFromContextAsync();
        });
        // Set minimum size
        captureTextButton.setMinimumSize(captureTextButton.getPreferredSize());
        buttonsPanel.add(captureTextButton);

        // "Edit References" button
        editReferencesButton = new JButton("Edit References");
        editReferencesButton.setToolTipText("Edit the files referenced by the output");
        editReferencesButton.setMnemonic(KeyEvent.VK_F);
        editReferencesButton.setEnabled(false);
        editReferencesButton.addActionListener(e -> {
            contextManager.editFilesFromContextAsync();
        });
        // Set minimum size
        editReferencesButton.setMinimumSize(editReferencesButton.getPreferredSize());
        buttonsPanel.add(editReferencesButton);

        // Add buttons panel to the right
        panel.add(buttonsPanel, BorderLayout.EAST);

        // We now use the MarkdownOutputPanel's text change listener instead
        // which is set up in buildLLMStreamScrollPane()

        return panel;
    }

    /**
     * Updates the state of capture buttons based on textarea content
     * 
     * If `ctx` is null it means we're processing a new response from the LLM
     * and we should parse our raw text for references instead
     */
    public void updateCaptureButtons(Context ctx) {
        String text = llmStreamArea.getText();
        boolean hasText = !text.isBlank();

        SwingUtilities.invokeLater(() -> {
            captureTextButton.setEnabled(hasText);
            var analyzer = contextManager == null ? null : contextManager.getAnalyzerNonBlocking();

            // Check for sources only if there's text
            if (hasText && analyzer != null) {
                // Use the sources method directly instead of a static method
                ContextFragment.VirtualFragment fragment;
                fragment = ctx == null
                        ? new ContextFragment.StringFragment(text, "temp")
                        : ctx.getParsedOutput().parsedFragment();
                // parsedOutput can be null (no text to capture for that action)
                var sources = fragment == null 
                        ? Set.<CodeUnit>of() 
                        : fragment.sources(analyzer, getProject().getRepo());
                editReferencesButton.setEnabled(!sources.isEmpty());
                // Update description with file names
                updateFilesDescriptionLabel(sources);
            } else {
                editReferencesButton.setEnabled(false);
                updateFilesDescriptionLabel(Set.of());
            }
        });
    }

    /**
     * Builds the history dropdown panel with template selections
     *
     * @return A panel containing the history dropdown button
     */
    // Constants for the history dropdown
    private static final int DROPDOWN_MENU_WIDTH = 1000; // Pixels
    private static final int TRUNCATION_LENGTH = 100;    // Characters - appropriate for 1000px width

    private JPanel buildHistoryDropdown() {
        JPanel historyPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JButton historyButton = new JButton("History ▼");
        historyButton.setToolTipText("Select a previous instruction from history");
        historyPanel.add(historyButton);

        // Show popup when button is clicked
        historyButton.addActionListener(e -> {
            logger.debug("History button clicked, creating menu");

            // Create a fresh popup menu each time
            JPopupMenu historyMenu = new JPopupMenu();

            // Get history items from project
            var project = getProject();
            if (project == null) {
                logger.warn("Cannot show history menu: project is null");
                return;
            }

            List<String> historyItems = project.loadTextHistory();
            logger.debug("History items loaded: {}", historyItems.size());

            if (historyItems.isEmpty()) {
                JMenuItem emptyItem = new JMenuItem("(No history items)");
                emptyItem.setEnabled(false);
                historyMenu.add(emptyItem);
            } else {
                // Iterate in reverse order so newest items appear at the bottom of the dropdown
                // This creates a more natural flow when the dropdown appears above the button
                for (int i = historyItems.size() - 1; i >= 0; i--) {
                    String item = historyItems.get(i);
                    // Use static truncation length
                    String displayText = item.length() > TRUNCATION_LENGTH ?
                            item.substring(0, TRUNCATION_LENGTH - 3) + "..." : item;

                    JMenuItem menuItem = new JMenuItem(displayText);
                    menuItem.setToolTipText(item); // Show full text on hover

                    menuItem.addActionListener(event -> {
                        commandInputField.setText(item);
                    });
                    historyMenu.add(menuItem);
                    logger.debug("Added menu item: {}", displayText);
                }
            }

            // Apply theme to the menu
            if (themeManager != null) {
                themeManager.registerPopupMenu(historyMenu);
            }

            // Use fixed width for menu
            historyMenu.setMinimumSize(new Dimension(DROPDOWN_MENU_WIDTH, 0));
            historyMenu.setPreferredSize(new Dimension(DROPDOWN_MENU_WIDTH, historyMenu.getPreferredSize().height));

            // Pack and show
            historyMenu.pack();

            logger.debug("Menu width set to fixed value: {}", DROPDOWN_MENU_WIDTH);

            // Show above the button instead of below
            historyMenu.show(historyButton, 0, -historyMenu.getPreferredSize().height);

            logger.debug("Menu shown with dimensions: {}x{}",
                         historyMenu.getWidth(), historyMenu.getHeight());
        });

        return historyPanel;
    }

    // This method is no longer needed as we use fixed width

    private void setInitialHistoryPanelWidth() {
        // Safety checks
        if (historySplitPane == null) {
            return;
        }

        // Don't override if we have a saved position from project settings
        if (getProject() != null && getProject().getHistorySplitPosition() > 0) {
            return;
        }

        historySplitPane.setResizeWeight(0.0); // left side can shrink/grow
        historySplitPane.setDividerLocation(0.2);

        // Re-validate to ensure the UI picks up changes
        historySplitPane.revalidate();
        historySplitPane.repaint();
    }

    /**
     * Be very careful to run any UI updates on the EDT
     * It's impossible to enforce this when we just hand out the JFrame reference
     * We should probably encapsulate what callers need and remove this
     */
    public JFrame getFrame() {
        assert SwingUtilities.isEventDispatchThread() : "Not on EDT";
        return frame;
    }

    public void focusInput() {
        SwingUtilities.invokeLater(() -> {
            this.commandInputField.requestFocus();
        });
    }

    /**
     * Prefills the command input field with the given text
     */
    public void prefillCommand(String command) {
        SwingUtilities.invokeLater(() -> {
            commandInputField.setText(command);
            runButton.requestFocus();
        });
    }

    /**
     * Sets the text in the commit message area
     */
    public void setCommitMessageText(String message) {
        SwingUtilities.invokeLater(() -> {
            gitPanel.setCommitMessageText(message);
        });
    }

    /**
     * Updates the references description label with a formatted list of files
     * Shows up to 3 file names, with "..." if there are more, and sets a tooltip with all names
     */
    private void updateFilesDescriptionLabel(Set<CodeUnit> sources) {
        SwingUtilities.invokeLater(() -> {
            if (sources == null || sources.isEmpty()) {
                captureDescriptionArea.setText("No references found");
                captureDescriptionArea.setToolTipText(null);
                return;
            }

            // Build both the short version (for display) and full version (for tooltip)
            var fileNames = sources.stream()
                    .map(CodeUnit::name)
                    .toList();

            StringBuilder displayText = new StringBuilder();

            if (fileNames.size() <= 3) {
                // Show all references if 3 or fewer
                displayText.append(String.join(", ", fileNames));
            } else {
                // Show first 3 references + "..." if more than 3
                displayText.append(String.join(", ", fileNames.subList(0, 3)));
                displayText.append(", ...");
            }

            // Set the text and tooltip
            captureDescriptionArea.setText(displayText.toString());

            // Only set tooltip if there are more than 3 files
            if (fileNames.size() > 3) {
                captureDescriptionArea.setToolTipText(String.join("\n", fileNames));
            } else {
                captureDescriptionArea.setToolTipText(null);
            }
        });
    }

    public void updateContextTable() {
        contextPanel.updateContextTable();
    }

    /**
     * Clears the context panel
     */

    public ContextManager getContextManager() {
        return contextManager;
    }

    /**
     * Get the list of selected fragments
     */
    public List<ContextFragment> getSelectedFragments() {
        return contextPanel.getSelectedFragments();
    }

    GitPanel getGitPanel() {
        return gitPanel;
    }

    /**
     * Shows a dialog for setting a custom AutoContext size (0-100).
     */
    public void showSetAutoContextSizeDialog() {
        var dialog = new JDialog(getFrame(), "Set AutoContext Size", true);
        dialog.setLayout(new BorderLayout());

        var panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        var label = new JLabel("Enter autocontext size (0-100):");
        panel.add(label, BorderLayout.NORTH);

        // Current AutoContext file count as the spinner's initial value
        var spinner = new JSpinner(new SpinnerNumberModel(
                contextManager.selectedContext().getAutoContextFileCount(),
                0, 100, 1
        ));
        panel.add(spinner, BorderLayout.CENTER);

        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var okButton = new JButton("OK");
        var cancelButton = new JButton("Cancel");

        okButton.addActionListener(ev -> {
            var newSize = (int) spinner.getValue();
            contextManager.setAutoContextFilesAsync(newSize);
            dialog.dispose();
        });

        cancelButton.addActionListener(ev -> dialog.dispose());
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        dialog.getRootPane().setDefaultButton(okButton);
        dialog.getRootPane().registerKeyboardAction(
                e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );

        dialog.add(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(getFrame());
        dialog.setVisible(true);
    }
}
