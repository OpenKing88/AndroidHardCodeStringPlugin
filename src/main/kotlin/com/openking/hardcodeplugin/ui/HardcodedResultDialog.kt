package com.openking.hardcodeplugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.util.ui.tree.TreeUtil
import com.openking.hardcodeplugin.model.RootHardcodedItem
import com.openking.hardcodeplugin.model.TreeNodeData
import com.openking.hardcodeplugin.processor.HardcodedStringReplacer
import com.openking.hardcodeplugin.scanner.StringXmlManager
import com.openking.hardcodeplugin.table.newKeyColumn
import com.openking.hardcodeplugin.table.oldKeyColumn
import com.openking.hardcodeplugin.table.selectColumn
import com.openking.hardcodeplugin.table.textColumn
import com.openking.hardcodeplugin.table.useNewKeyColumn
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class HardcodedResultDialog(
    var project: Project,
    var items: List<RootHardcodedItem>
) : DialogWrapper(project) {

    private val treeTable: TreeTable

    init {
        title = "扫描字符串硬编码"

        val root = createRoot(items)

        val model = ListTreeTableModelOnColumns(
            root,
            arrayOf(selectColumn,textColumn, newKeyColumn, oldKeyColumn, useNewKeyColumn)
        )

        treeTable = TreeTable(model)
        initTreeTable()
        setupMouseListener(project)
        init()
    }


    override fun createActions(): Array<Action> {
        val applyAction = object : DialogWrapperAction("全自动替换硬编码") {
            override fun doAction(e: ActionEvent?) {
                var result: HardcodedStringReplacer.ReplacementResult? = null
                ProgressManager.getInstance().run(object : Task.Modal(project, "正在执行重构", true) {
                    override fun run(indicator: ProgressIndicator) {
                        val xmlManager = StringXmlManager(project)

                        // 第一步：同步到 strings.xml
                        indicator.text = "步骤 1/2: 正在写入 strings.xml..."
                        xmlManager.syncDataToXml(items.filter { it.selected }, indicator)
                        openStringsXmlFile()

                        // 第二步：替换源代码中的引用
                        indicator.text = "步骤 2/2: 正在替换源码中的硬编码..."
                        indicator.fraction = 0.0
                        result = HardcodedStringReplacer.replaceAll(
                            project,
                            items.filter { it.selected }
                        ) { current: Int, total: Int, file: String ->
                            indicator.text = "$current/$total---$file"
                        }
                        openFileAtLine(
                            project = project,
                            filePath = items.first().path,
                            lineNumber = items.first().items.first().lineNumber
                        )
                    }

                    override fun onSuccess() {
                        Messages.showInfoMessage(
                            project,
                            "重构完成！总共替换了${result?.totalLocations ?: 0}处硬编码\n" +
                                    "成功：${result?.successCount ?: 0}\n" +
                                    "失败：${result?.errors?.size ?: 0}\n" +
                                    "请检查代码并导入必要的 R 文件引用。\n" +
                                    "特别提醒：\n" +
                                    "在compose中的普通代码块需要使用getString的方式引用，如需要context可在上层的compose中声明一个context的变量来使用\n" +
                                    "            val context = LocalResources.current\n" +
                                    "            Button(\n" +
                                    "                onClick = {\n" +
                                    "                    context.getString(R.string.b2_0538j08).toast()\n" +
                                    "                }\n" +
                                    "            ) {\n" +
                                    "                Text(\n" +
                                    "                    text = stringResource(R.string.v7_cr6gi),\n" +
                                    "                    color = TextBlack,\n" +
                                    "                    fontSize = 18.sp,\n" +
                                    "                    fontWeight = FontWeight.Medium\n" +
                                    "                )\n" +
                                    "            }",
                            "成功"
                        )
                        this@HardcodedResultDialog.close(OK_EXIT_CODE)
                    }
                })
            }
        }
        return arrayOf(
            applyAction,
            cancelAction
        )
    }

    private fun openCodeFile(virtualFile: VirtualFile){
        ApplicationManager.getApplication().invokeLater {
            // 打开文件，并跳转到最后一行（或者直接打开）
            val descriptor = OpenFileDescriptor(project, virtualFile)
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        }
    }

    /**
     * 打开 strings.xml 文件并准确定位
     */
    private fun openStringsXmlFile() {
        val xmlManager = StringXmlManager(project)
        val xmlFile = xmlManager.getOrCreateStringsXmlPsiFile()
        val virtualFile = xmlFile?.virtualFile

        if (virtualFile != null) {
            ApplicationManager.getApplication().invokeLater {
                // 打开文件，并跳转到最后一行（或者直接打开）
                val descriptor = OpenFileDescriptor(project, virtualFile)
                FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
            }
        } else {
            Messages.showWarningDialog(project, "找不到 strings.xml 文件", "提示")
        }
    }

    /**
     * 优化的树形结构构建
     * 说明：
     * - 如果有多个相同的文本，创建 RootHardcodedItem 作为父节点，HardcodedItem 作为子节点
     * - 如果只有一个项目，直接创建 HardcodedItem 节点，无需中间父节点
     */
    private fun createRoot(groups: List<RootHardcodedItem>): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode("root")

        groups.forEach { rootItem ->
            if (rootItem.items.size > 1) {
                // 多个重复的项目：创建分组父节点
                val parent = DefaultMutableTreeNode(TreeNodeData.Group(rootItem))
                rootItem.items.forEach { item ->
                    val child = DefaultMutableTreeNode(TreeNodeData.Item(item, rootItem))
                    parent.add(child)
                }
                root.add(parent)
            } else {
                // 单个项目：直接添加，无需父节点
                rootItem.items.firstOrNull() ?: return@forEach
                val child = DefaultMutableTreeNode(TreeNodeData.Group(rootItem))
                root.add(child)
            }
        }

        return root
    }

    private fun initTreeTable() {
        treeTable.tree.isRootVisible = false
        treeTable.tree.showsRootHandles = true
        treeTable.rowHeight = 26
        treeTable.columnModel.getColumn(0).preferredWidth = 120
        treeTable.columnModel.getColumn(1).preferredWidth = 350
        treeTable.columnModel.getColumn(2).preferredWidth = 200
        treeTable.columnModel.getColumn(3).preferredWidth = 200
        treeTable.columnModel.getColumn(4).preferredWidth = 120
        treeTable.setShowGrid(true)
        TreeUtil.expandAll(treeTable.tree)
    }

    /**
     * 设置鼠标事件处理
     * - 单击有子节点的行：切换展开/折叠
     * - 双击无子节点的行：定位到文件位置
     */
    private fun setupMouseListener(project: Project) {
        treeTable.addMouseListener(object : MouseListener {
            override fun mouseClicked(e: MouseEvent) {
                // 1. 获取点击的行索引
                val row = treeTable.rowAtPoint(e.point)
                if (row < 0) return

                // 2. 获取点击的列索引 (View Index)
                val column = treeTable.columnAtPoint(e.point)

                // 3. 设置只有点击第 1 列（即 Text 列）才生效
                if (column != 1) return

                val path = treeTable.tree.getPathForRow(row)
                val node = path?.lastPathComponent as? DefaultMutableTreeNode ?: return
                val hasChildren = node.childCount > 0

                when (e.clickCount) {
                    1 if hasChildren -> {
                        // 单击有子节点：切换展开/折叠
                        toggleTreeNode(path)
                    }

                    2 if !hasChildren -> {
                        // 双击无子节点：定位到文件
                        val nodeData = node.userObject
                        if (nodeData is TreeNodeData.Item) {
                            openFileAtLine(project, nodeData.item.path, nodeData.item.lineNumber)
                        } else if (nodeData is TreeNodeData.Group) {
                            openFileAtLine(project, nodeData.rootItem.path, nodeData.rootItem.items.first().lineNumber)
                        }
                    }
                }
            }

            override fun mousePressed(e: MouseEvent) {}
            override fun mouseReleased(e: MouseEvent) {}
            override fun mouseEntered(e: MouseEvent) {}
            override fun mouseExited(e: MouseEvent) {}
        })
    }

    private fun toggleTreeNode(path: TreePath) {
        val tree = treeTable.tree
        if (tree.isExpanded(path)) {
            tree.collapsePath(path)
        } else {
            tree.expandPath(path)
        }
    }

    private fun openFileAtLine(project: Project, filePath: String, lineNumber: Int) {
        ApplicationManager.getApplication().invokeLater {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            if (virtualFile != null) {
                val descriptor = OpenFileDescriptor(project, virtualFile, lineNumber - 1, 0)
                FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
            }
        }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(900, 500)
        panel.add(JScrollPane(treeTable), BorderLayout.CENTER)
        return panel
    }
}