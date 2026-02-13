package com.openking.hardcodeplugin.table

import com.intellij.util.ui.ColumnInfo
import javax.swing.tree.DefaultMutableTreeNode
import com.openking.hardcodeplugin.model.TreeNodeData

/**
 * 是否处理该字符串
 *
 */
val selectColumn = object : ColumnInfo<DefaultMutableTreeNode, Boolean>("是否处理") {

    override fun getColumnClass() = java.lang.Boolean::class.java

    override fun valueOf(node: DefaultMutableTreeNode): Boolean {
        return when (val data = node.userObject) {
            is TreeNodeData.Group -> data.rootItem.selected
            is TreeNodeData.Item -> data.rootItem?.selected ?: true
            else -> false
        }
    }

    override fun isCellEditable(node: DefaultMutableTreeNode?): Boolean {
        return when (node?.userObject) {
            is TreeNodeData.Group -> true
            else -> false
        }
    }

    override fun setValue(node: DefaultMutableTreeNode?, value: Boolean?) {
        val data = node?.userObject
        if (data is TreeNodeData.Group && value != null) {
            data.rootItem.selected = value
        }
    }
}

/**
 * 字符串列设置
 * 处理两种节点类型：
 * - Group：显示分组的文本
 * - Item：显示行号和具体文本，如果是单独项目也显示 newKey/oldKey/useNewKey 的值
 */
val textColumn = object : ColumnInfo<DefaultMutableTreeNode, String>("字符串") {
    override fun valueOf(node: DefaultMutableTreeNode): String {
        return when (val data = node.userObject) {
            is TreeNodeData.Group -> (if (data.rootItem.items.size>1) "◉ " else "" )+ data.rootItem.text
            is TreeNodeData.Item -> {
                val lineInfo = "Line: ${data.item.lineNumber}"
                "       └─→$lineInfo"
            }
            else -> ""
        }
    }
}

/**
 * newKey 列设置
 * - 仅分组节点可编辑和显示
 * - 单个项目节点显示其关联的 RootHardcodedItem 的 newKey
 */
val newKeyColumn = object : ColumnInfo<DefaultMutableTreeNode, String>("NewKey") {
    override fun valueOf(node: DefaultMutableTreeNode): String {
        return when (val data = node.userObject) {
            is TreeNodeData.Group -> data.rootItem.newKey
            is TreeNodeData.Item -> data.rootItem?.newKey ?: ""
            else -> ""
        }
    }

    override fun isCellEditable(node: DefaultMutableTreeNode?): Boolean {
        val data = node?.userObject
        return data is TreeNodeData.Group
    }

    override fun setValue(node: DefaultMutableTreeNode?, value: String?) {
        val data = node?.userObject
        if (data is TreeNodeData.Group && value != null) {
            data.rootItem.newKey = value
        }
    }
}

/**
 * oldKey 列设置（strings.xml 中的 Key）
 * - 仅分组节点显示
 * - 单个项目节点显示其关联的 RootHardcodedItem 的 oldKey
 */
val oldKeyColumn = object : ColumnInfo<DefaultMutableTreeNode, String>("OldKey") {
    override fun valueOf(node: DefaultMutableTreeNode): String {
        return when (val data = node.userObject) {
            is TreeNodeData.Group -> data.rootItem.oldKey ?: "不存在"
            is TreeNodeData.Item -> data.rootItem?.oldKey ?: "不存在"
            else -> ""
        }
    }
}

/**
 * 是否使用 newKey 列设置
 * - 仅分组节点可编辑和显示
 * - 单个项目节点显示其关联的 RootHardcodedItem 的 useNewKey
 * - 当 oldKey 为空时，禁用编辑（表示没有对应的旧 key，必须使用 newKey）
 */
val useNewKeyColumn = object : ColumnInfo<DefaultMutableTreeNode, Boolean>("是否使用newKey") {

    override fun getColumnClass() = java.lang.Boolean::class.java

    override fun valueOf(node: DefaultMutableTreeNode): Boolean {
        return when (val data = node.userObject) {
            is TreeNodeData.Group -> data.rootItem.useNewKey
            is TreeNodeData.Item -> data.rootItem?.useNewKey ?: true
            else -> false
        }
    }

    override fun isCellEditable(node: DefaultMutableTreeNode?): Boolean {
        return when (val data = node?.userObject) {
            is TreeNodeData.Group -> {
                // 仅当存在 oldKey 时才允许编辑（选择是否使用 newKey）
                !data.rootItem.oldKey.isNullOrEmpty()
            }
            is TreeNodeData.Item -> {
                // 单个项目节点不可编辑此列
                false
            }
            else -> false
        }
    }

    override fun setValue(node: DefaultMutableTreeNode?, value: Boolean?) {
        val data = node?.userObject
        if (data is TreeNodeData.Group && value != null) {
            data.rootItem.useNewKey = value
        }
    }
}



