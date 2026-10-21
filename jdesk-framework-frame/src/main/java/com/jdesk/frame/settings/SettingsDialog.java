package com.jdesk.frame.settings;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationManager;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.i18n.I18n;
import com.jdesk.core.i18n.LocaleListener;
import com.jdesk.core.message.MessageBus;
import com.jdesk.core.message.MessageBusConnection;
import com.jdesk.frame.ui.components.JdfUi;
import com.jdesk.frame.ui.scale.JdfFonts;
import com.jdesk.frame.ui.scale.JdfScale;
import com.jdesk.util.text.StringUtil;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.*;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * Main settings dialog, modeled on IntelliJ Platform's: a searchable settings tree on the left and the selected page
 * on the right. Switching pages checks for unapplied changes, a search shows matching pages with their parent groups,
 * and the bottom buttons apply and reset. While open it follows {@link LocaleListener} and relabels itself in place,
 * unsubscribing in {@link #dispose()}.
 */
public class SettingsDialog extends JDialog {

    private final SettingsGroup rootGroup;
    private final List<Configurable> allConfigurables;
    /** Pages whose component was created in this dialog; only these are checked, applied and discarded. */
    private final Set<Configurable> openedConfigurables = Collections.newSetFromMap(new IdentityHashMap<>());
    private Configurable selectedConfigurable;

    // UI components
    private JTree settingsTree;
    private DefaultTreeModel treeModel;
    private JTextField searchField;
    private JPanel contentPanel;
    private JLabel headerLabel;
    private JLabel descriptionLabel;
    private JPanel currentConfigPanel;
    private JButton resetButton;
    private JButton applyButton;
    private JButton okButton;
    private JButton cancelButton;
    private javax.swing.Timer modificationTimer;
    private MessageBusConnection localeConnection;

    private boolean wasApplied = false;
    private final Runnable afterApply;

    public SettingsDialog(JFrame owner, SettingsGroup rootGroup) {
        this(owner, rootGroup, null);
    }

    /**
     * @param afterApply called after changes are actually applied, by Apply or OK, so the owner can save them at
     *                   once; may be {@code null}
     */
    public SettingsDialog(JFrame owner, SettingsGroup rootGroup, Runnable afterApply) {
        super(owner, true);
        this.rootGroup = rootGroup;
        this.allConfigurables = rootGroup.collectConfigurables();
        this.afterApply = afterApply;

        setSize(JdfUi.size(900, 620));
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        buildUI();
        relabel();
        selectFirstConfigurable();
        subscribeToLocaleChanges();
    }

    /** Sets the title, search placeholder and buttons in the current language. */
    private void relabel() {
        setTitle(I18n.message("frame.settings.title"));
        searchField.putClientProperty("JTextField.placeholderText", I18n.message("frame.settings.searchPlaceholder"));
        resetButton.setText(I18n.message("frame.settings.reset"));
        applyButton.setText(I18n.message("frame.dialog.apply"));
        okButton.setText(I18n.message("frame.dialog.ok"));
        cancelButton.setText(I18n.message("frame.dialog.cancel"));
    }

    private void subscribeToLocaleChanges() {
        Application application = ApplicationManager.getApplicationIfCreated();
        if (application == null || application.isDisposed()) {
            return;
        }
        MessageBus bus = application.getMessageBus();
        if (bus == null || bus.isClosedForNewConnections()) {
            return;
        }
        localeConnection = bus.connect();
        // Don't use invokeAndWait: I18n.switchTo still holds its class lock, so a switch on the EDT would deadlock.
        localeConnection.subscribe(LocaleListener.class,
                (oldLanguage, newLanguage) -> SwingUtilities.invokeLater(this::onLocaleChanged));
    }

    /** Relabels the dialog, tree and header; rebuilds the current page only if it has no unapplied changes. */
    private void onLocaleChanged() {
        if (localeConnection == null) {
            return; // Already closed, for example after OK applied a language.
        }
        relabel();
        // Don't use reload(): it collapses the tree and clears the selection.
        for (TreeNode node : Collections.list(((DefaultMutableTreeNode) treeModel.getRoot()).preorderEnumeration())) {
            treeModel.nodeChanged(node);
        }
        if (selectedConfigurable == null) {
            return;
        }
        if (selectedConfigurable.isModified()) {
            showHeader(selectedConfigurable);
        } else {
            showConfigurable(selectedConfigurable);
        }
    }

    private void buildUI() {
        Container cp = getContentPane();
        cp.setLayout(new BorderLayout());

        // ---- Search bar ----
        JPanel searchPanel = new JPanel(new BorderLayout());
        searchPanel.setBorder(JdfUi.empty(8));
        searchField = new JTextField();
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { filterTree(); }
            @Override public void removeUpdate(DocumentEvent e) { filterTree(); }
            @Override public void changedUpdate(DocumentEvent e) { filterTree(); }
        });
        searchPanel.add(searchField, BorderLayout.CENTER);
        cp.add(searchPanel, BorderLayout.NORTH);

        // ---- Split pane: settings tree | page content ----
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(JdfScale.scale(220));
        splitPane.setDividerSize(Math.max(1, JdfScale.scale(1)));

        treeModel = buildTreeModel(rootGroup);
        settingsTree = new JTree(treeModel);
        settingsTree.setRootVisible(false);
        settingsTree.setShowsRootHandles(true);
        settingsTree.setCellRenderer(new SettingsTreeCellRenderer());
        settingsTree.addTreeSelectionListener(e -> onTreeSelection());
        JScrollPane treeScroll = new JScrollPane(settingsTree);
        treeScroll.setBorder(JdfUi.matteBorder(0, 0, 0, 1, new Color(200, 200, 200)));
        splitPane.setLeftComponent(treeScroll);

        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setBorder(JdfUi.empty(16, 20, 16, 20));

        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));
        headerLabel = new JLabel();
        headerLabel.setFont(JdfFonts.derive(headerLabel.getFont(), Font.BOLD, 16f));
        headerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        descriptionLabel = new JLabel();
        descriptionLabel.setFont(JdfFonts.label(12f));
        descriptionLabel.setForeground(new Color(120, 120, 120));
        descriptionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        descriptionLabel.setBorder(JdfUi.empty(4, 0, 0, 0));
        headerPanel.add(headerLabel);
        headerPanel.add(descriptionLabel);
        headerPanel.add(JdfUi.verticalStrut(8));
        headerPanel.add(new JSeparator());
        headerPanel.add(JdfUi.verticalStrut(12));

        rightPanel.add(headerPanel, BorderLayout.NORTH);

        currentConfigPanel = new JPanel(new BorderLayout());
        contentPanel = new JPanel(new BorderLayout());
        contentPanel.add(currentConfigPanel, BorderLayout.CENTER);
        rightPanel.add(contentPanel, BorderLayout.CENTER);

        splitPane.setRightComponent(rightPanel);
        cp.add(splitPane, BorderLayout.CENTER);

        // ---- Bottom buttons ----
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, JdfScale.scale(8), JdfScale.scale(8)));
        buttonPanel.setBorder(BorderFactory.createCompoundBorder(
                JdfUi.matteBorder(1, 0, 0, 0, new Color(200, 200, 200)),
                JdfUi.empty(8, 16, 8, 16)));

        resetButton = new JButton();
        resetButton.addActionListener(e -> resetCurrent());
        buttonPanel.add(resetButton);

        applyButton = new JButton();
        applyButton.setEnabled(false);
        applyButton.addActionListener(e -> applyAll());
        buttonPanel.add(applyButton);

        okButton = new JButton();
        okButton.addActionListener(e -> { applyAll(); wasApplied = true; dispose(); });
        buttonPanel.add(okButton);

        cancelButton = new JButton();
        cancelButton.addActionListener(e -> dispose());
        buttonPanel.add(cancelButton);

        cp.add(buttonPanel, BorderLayout.SOUTH);
    }

    // ============== Tree model ==============

    private DefaultTreeModel buildTreeModel(SettingsGroup root) {
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode(root);
        addChildren(rootNode, root);
        return new DefaultTreeModel(rootNode);
    }

    private void addChildren(DefaultMutableTreeNode parentNode, SettingsGroup group) {
        for (Object child : group.getChildren()) {
            if (child instanceof SettingsGroup g) {
                DefaultMutableTreeNode groupNode = new DefaultMutableTreeNode(g);
                parentNode.add(groupNode);
                addChildren(groupNode, g);
            } else if (child instanceof Configurable c) {
                parentNode.add(new DefaultMutableTreeNode(c));
            }
        }
    }

    // ============== Selection ==============

    private void selectFirstConfigurable() {
        for (int i = 0; i < settingsTree.getRowCount(); i++) {
            settingsTree.expandRow(i);
        }
        findAndSelectFirstConfigurable((DefaultMutableTreeNode) treeModel.getRoot());
    }

    private boolean findAndSelectFirstConfigurable(DefaultMutableTreeNode node) {
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            if (child.getUserObject() instanceof Configurable c) {
                TreePath path = new TreePath(child.getPath());
                settingsTree.setSelectionPath(path);
                return true;
            }
            if (findAndSelectFirstConfigurable(child)) return true;
        }
        return false;
    }

    private void onTreeSelection() {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode)
                settingsTree.getLastSelectedPathComponent();
        if (node == null || !(node.getUserObject() instanceof Configurable c)) {
            return;
        }

        if (selectedConfigurable != null && selectedConfigurable != c
                && selectedConfigurable.isModified()) {
            int result = JOptionPane.showConfirmDialog(this,
                    I18n.message("frame.settings.unsavedMessage", "page", selectedConfigurable.getDisplayName()),
                    I18n.message("frame.settings.unsavedTitle"), JOptionPane.YES_NO_CANCEL_OPTION);
            if (result == JOptionPane.YES_OPTION) {
                selectedConfigurable.apply();
            } else if (result == JOptionPane.NO_OPTION) {
                selectedConfigurable.reset();
            } else {
                // Cancelling or closing the prompt keeps the previous page selected.
                settingsTree.setSelectionPath(findNodePath(selectedConfigurable));
                return;
            }
        }

        showConfigurable(c);
    }

    private TreePath findNodePath(Configurable target) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
        return findNodePathRecursive(root, target);
    }

    private TreePath findNodePathRecursive(DefaultMutableTreeNode node, Configurable target) {
        if (node.getUserObject() == target) {
            return new TreePath(node.getPath());
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            TreePath found = findNodePathRecursive(
                    (DefaultMutableTreeNode) node.getChildAt(i), target);
            if (found != null) return found;
        }
        return null;
    }

    private void showConfigurable(Configurable c) {
        selectedConfigurable = c;
        showHeader(c);
        currentConfigPanel.removeAll();
        currentConfigPanel.add(c.createComponent(), BorderLayout.CENTER);
        openedConfigurables.add(c);
        currentConfigPanel.revalidate();
        currentConfigPanel.repaint();

        // Poll for modifications instead of requiring change events from every page.
        if (modificationTimer != null) {
            modificationTimer.stop();
        }
        modificationTimer = new javax.swing.Timer(300, e -> {
            applyButton.setEnabled(hasAnyModifications());
        });
        modificationTimer.setRepeats(true);
        modificationTimer.start();
    }

    private void showHeader(Configurable c) {
        headerLabel.setText(c.getDisplayName());
        String desc = c.getDescription();
        descriptionLabel.setText(desc != null ? desc : "");
        descriptionLabel.setVisible(desc != null);
    }

    // ============== Search filter ==============

    private void filterTree() {
        String query = searchField.getText().trim().toLowerCase(Locale.ROOT);
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
        root.removeAllChildren();

        if (query.isEmpty()) {
            addChildren(root, rootGroup);
        } else {
            // Show only matching pages, with the groups on their path.
            filterRecursive(root, rootGroup, query, new Stack<>());
        }

        treeModel.reload();
        for (int i = 0; i < settingsTree.getRowCount(); i++) {
            settingsTree.expandRow(i);
        }
        findAndSelectFirstConfigurable(root);
    }

    private void filterRecursive(DefaultMutableTreeNode parentNode, SettingsGroup group,
                                  String query, Stack<String> path) {
        path.push(group.getDisplayName());
        for (Object child : group.getChildren()) {
            if (child instanceof SettingsGroup g) {
                if (anyDescendantMatches(g, query)) {
                    DefaultMutableTreeNode groupNode = new DefaultMutableTreeNode(g);
                    parentNode.add(groupNode);
                    filterRecursive(groupNode, g, query, path);
                }
            } else if (child instanceof Configurable c) {
                if (matchesQuery(c, query)) {
                    parentNode.add(new DefaultMutableTreeNode(c));
                }
            }
        }
        path.pop();
    }

    private boolean anyDescendantMatches(SettingsGroup group, String query) {
        for (Configurable c : group.collectConfigurables()) {
            if (matchesQuery(c, query)) return true;
        }
        return false;
    }

    private boolean matchesQuery(Configurable c, String query) {
        if (StringUtil.containsIgnoreCase(c.getDisplayName(), query)) return true;
        if (c instanceof SearchableConfigurable sc) {
            for (String keyword : sc.getSearchKeywords()) {
                if (StringUtil.containsIgnoreCase(keyword, query)) return true;
            }
        }
        return false;
    }

    // ============== Apply and reset ==============

    private void applyAll() {
        boolean applied = false;
        for (Configurable c : openedInTreeOrder()) {
            if (c.isModified()) {
                c.apply();
                applied = true;
            }
        }
        applyButton.setEnabled(false);
        if (applied && afterApply != null) {
            afterApply.run();
        }
    }

    private void resetCurrent() {
        if (selectedConfigurable != null) {
            selectedConfigurable.reset();
            showConfigurable(selectedConfigurable);
        }
    }

    private boolean hasAnyModifications() {
        for (Configurable c : openedInTreeOrder()) {
            if (c.isModified()) return true;
        }
        return false;
    }

    /** Resets modified pages when the dialog closes without OK, so the next open doesn't show cancelled edits. */
    private void discardUnapplied() {
        for (Configurable c : openedInTreeOrder()) {
            if (c.isModified()) {
                c.reset();
            }
        }
        openedConfigurables.clear();
    }

    private List<Configurable> openedInTreeOrder() {
        return allConfigurables.stream().filter(openedConfigurables::contains).toList();
    }

    /** Shows the dialog and returns whether the user applied the changes with OK. */
    public boolean showAndGet() {
        setVisible(true);
        return wasApplied;
    }

    @Override
    public void dispose() {
        if (localeConnection != null) {
            Disposer.dispose(localeConnection);
            localeConnection = null;
        }
        if (modificationTimer != null) {
            modificationTimer.stop();
            modificationTimer = null;
        }
        try {
            discardUnapplied();
        } finally {
            super.dispose();
        }
    }

    // ============== Tree renderer ==============

    private static class SettingsTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                                                       boolean sel, boolean expanded,
                                                       boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            Object userObj = node.getUserObject();

            if (userObj instanceof SettingsGroup g) {
                setText(g.getDisplayName());
                setIcon(g.getIcon());
                setFont(getFont().deriveFont(Font.BOLD));
            } else if (userObj instanceof Configurable c) {
                setText(c.getDisplayName());
                setIcon(c.getIcon());
                setFont(getFont().deriveFont(Font.PLAIN));
                setBorder(JdfUi.empty(1, 16, 1, 0));
            }
            return this;
        }
    }
}
