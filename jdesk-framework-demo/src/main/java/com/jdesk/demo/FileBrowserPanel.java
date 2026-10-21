package com.jdesk.demo;

import com.jdesk.core.app.Application;
import com.jdesk.util.text.StringUtil;
import com.jdesk.core.vfs.LocalFileSystem;
import com.jdesk.core.vfs.VirtualFile;
import com.jdesk.core.vfs.VirtualFileManager;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * VFS-backed file browser tree component.
 * <p>
 * Provides {@link #createFileBrowserTree(Application)} as a factory method
 * that builds a {@link JTree} backed by the Virtual File System,
 * showing the local file system starting from the user's home directory.
 * Children are loaded lazily on tree expansion.
 */
public final class FileBrowserPanel {

    private FileBrowserPanel() {}

    /**
     * Create a JTree backed by the Virtual File System.
     * Shows the local file system starting from the user's home directory.
     */
    public static JTree createFileBrowserTree(Application app) {
        VirtualFileManager vfm = VirtualFileManager.getInstance();
        LocalFileSystem lfs = (LocalFileSystem) vfm.getFileSystem(LocalFileSystem.PROTOCOL);

        // Root: user home
        String userHome = System.getProperty("user.home");
        VirtualFile rootFile = lfs.findFileByPath(userHome);
        if (rootFile == null) {
            // Fallback to file system roots
            VirtualFile[] roots = lfs.getRoots();
            rootFile = roots.length > 0 ? roots[0] : null;
        }

        VirtualFileTreeNode root = new VirtualFileTreeNode(rootFile, true);
        JTree tree = new JTree(new VirtualFileTreeModel(root));
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);

        // Custom renderer showing name + size
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value,
                                                          boolean sel, boolean expanded,
                                                          boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                if (value instanceof VirtualFileTreeNode node) {
                    VirtualFile f = node.file;
                    String label = f.getName();
                    if (!f.isDirectory() && f.getLength() > 0) {
                        label += "  (" + StringUtil.formatFileSize(f.getLength()) + ")";
                    }
                    setText(label);
                    setIcon(f.isDirectory()
                            ? UIManager.getIcon("FileView.directoryIcon")
                            : UIManager.getIcon("FileView.fileIcon"));
                }
                return this;
            }
        });

        // Lazy-load children when expanding
        tree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent event) {
                Object last = event.getPath().getLastPathComponent();
                if (last instanceof VirtualFileTreeNode node) {
                    node.ensureChildrenLoaded();
                    ((VirtualFileTreeModel) tree.getModel()).nodeStructureChanged(node);
                }
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent event) {
            }
        });

        return tree;
    }

    // ============== VFS Tree Model ==============

    /** A node in the VFS-backed tree. */
    public static class VirtualFileTreeNode {
        final VirtualFile file;
        final boolean isDirectory;
        VirtualFileTreeNode[] children;
        boolean childrenLoaded;

        public VirtualFileTreeNode(VirtualFile file, boolean isDirectory) {
            this.file = file;
            this.isDirectory = isDirectory;
            this.children = new VirtualFileTreeNode[0];
            this.childrenLoaded = !isDirectory; // files are "loaded" (they have no children)
        }

        void ensureChildrenLoaded() {
            if (childrenLoaded || !isDirectory) return;
            VirtualFile[] childFiles = file.getChildren();
            children = new VirtualFileTreeNode[childFiles.length];
            for (int i = 0; i < childFiles.length; i++) {
                children[i] = new VirtualFileTreeNode(childFiles[i], childFiles[i].isDirectory());
            }
            childrenLoaded = true;
        }
    }

    /** TreeModel backed by VirtualFileTreeNode. */
    public static class VirtualFileTreeModel implements TreeModel {
        private final VirtualFileTreeNode root;
        private final List<TreeModelListener> listeners = new ArrayList<>();

        VirtualFileTreeModel(VirtualFileTreeNode root) {
            this.root = root;
            root.ensureChildrenLoaded(); // pre-load first level
        }

        @Override
        public Object getRoot() { return root; }

        @Override
        public Object getChild(Object parent, int index) {
            VirtualFileTreeNode node = (VirtualFileTreeNode) parent;
            node.ensureChildrenLoaded();
            return node.children[index];
        }

        @Override
        public int getChildCount(Object parent) {
            VirtualFileTreeNode node = (VirtualFileTreeNode) parent;
            if (!node.isDirectory) return 0;
            node.ensureChildrenLoaded();
            return node.children.length;
        }

        @Override
        public boolean isLeaf(Object node) {
            return !((VirtualFileTreeNode) node).isDirectory;
        }

        @Override
        public int getIndexOfChild(Object parent, Object child) {
            VirtualFileTreeNode p = (VirtualFileTreeNode) parent;
            p.ensureChildrenLoaded();
            for (int i = 0; i < p.children.length; i++) {
                if (p.children[i] == child) return i;
            }
            return -1;
        }

        @Override
        public void valueForPathChanged(TreePath path, Object newValue) {}

        @Override
        public void addTreeModelListener(TreeModelListener l) { listeners.add(l); }

        @Override
        public void removeTreeModelListener(TreeModelListener l) { listeners.remove(l); }

        void nodeStructureChanged(VirtualFileTreeNode node) {
            TreeModelEvent e = new TreeModelEvent(this, getPathToNode(node));
            for (TreeModelListener l : listeners) {
                l.treeStructureChanged(e);
            }
        }

        private TreePath getPathToNode(VirtualFileTreeNode node) {
            List<VirtualFileTreeNode> path = new ArrayList<>();
            collectPath(root, node, path);
            return new TreePath(path.toArray());
        }

        private boolean collectPath(VirtualFileTreeNode current, VirtualFileTreeNode target,
                                     List<VirtualFileTreeNode> path) {
            path.add(current);
            if (current == target) return true;
            current.ensureChildrenLoaded();
            for (VirtualFileTreeNode child : current.children) {
                if (collectPath(child, target, path)) return true;
            }
            path.remove(path.size() - 1);
            return false;
        }
    }
}
