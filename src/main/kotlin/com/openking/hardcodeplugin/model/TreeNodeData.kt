package com.openking.hardcodeplugin.model

/**
 * 树节点数据包装类
 * 用于统一处理分组节点和单个项目节点，提高代码的优雅性
 */
sealed class TreeNodeData {
    /**
     * 分组节点：表示多个相同文本的分组
     * 通常作为父节点，包含多个 HardcodedItem
     */
    data class Group(val rootItem: RootHardcodedItem) : TreeNodeData()

    /**
     * 单个项目节点：表示一个具体的硬编码字符串
     * 可以是单独的项目（无父节点），也可以是分组中的一个子项
     *
     * @param item 硬编码项目数据
     * @param rootItem 如果是单独的项目（无父节点），这里存储对应的 RootHardcodedItem
     *                 这样便于在列中访问 newKey、oldKey、useNewKey 等分组级别的数据
     */
    data class Item(
        val item: HardcodedItem,
        val rootItem: RootHardcodedItem? = null
    ) : TreeNodeData()
}