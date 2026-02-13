package com.openking.hardcodeplugin.scanner

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiManager
import com.intellij.psi.XmlElementFactory
import com.intellij.psi.xml.XmlFile
import com.intellij.refactoring.RefactoringFactory
import com.openking.hardcodeplugin.model.RootHardcodedItem

/**
 * 管理 strings.xml 文件的读写
 *
 * 功能：
 * - 创建 strings.xml（如果不存在）
 * - 查询 strings.xml 中是否存在指定 value
 * - 添加或更新 strings.xml 中的 key-value 对
 * - 重命名 key
 */

class StringXmlManager(private val project: Project) {

    /**
     * 第一步：同步数据到 XML，并为第二步替换源码做准备
     */
    fun syncDataToXml(rootItems: List<RootHardcodedItem>, indicator: ProgressIndicator? = null) {
        // 设置初始进度
        indicator?.isIndeterminate = false

        WriteCommandAction.runWriteCommandAction(project, "Sync Strings to XML", null, {
            val xmlFile = getOrCreateStringsXmlPsiFile() ?: return@runWriteCommandAction
            val rootTag = xmlFile.rootTag ?: return@runWriteCommandAction
            val factory = XmlElementFactory.getInstance(project)

            rootItems.forEachIndexed { index, item ->
                // 更新进度条描述文本和百分比
                indicator?.text = "正在处理: ${item.text.take(20)}..."
                indicator?.fraction = (index + 1).toDouble() / rootItems.size

                val oldKey = item.oldKey
                val newKey = item.newKey

                if (!item.useNewKey && !oldKey.isNullOrEmpty()) {
                    item.newKey = oldKey
                } else if (!oldKey.isNullOrEmpty()) {
                    if (oldKey != newKey) {
                        renameResourceKey(oldKey, newKey, this)
                    }
                } else if (oldKey == null) {
                    val existingTag = rootTag.findSubTags("string").find {
                        it.getAttributeValue("name") == newKey
                    }
                    if (existingTag == null) {
                        val escapedValue = escapeAndroidString(item.text)
                        val newTag = factory.createTagFromText("<string name=\"$newKey\">$escapedValue</string>")
                        rootTag.addSubTag(newTag, false)
                    }
                }
            }
        })
    }
    /**
     * 场景：用户修改了 Key 名 (oldKey -> newKey)
     * 利用 IDE 的 Rename 重构功能，自动更新全局引用
     */
    fun renameResourceKey(oldKey: String, newKey: String, stringXmlManager: StringXmlManager): Boolean {
        val xmlFile = stringXmlManager.getOrCreateStringsXmlPsiFile() ?: return false
        val rootTag = xmlFile.rootTag ?: return false

        // 1. 直接定位到那个特殊的 <string name="oldKey"> 标签
        val targetTag = rootTag.findSubTags("string").find {
            it.getAttributeValue("name") == oldKey
        } ?: return false

        // 2. 获取 name 属性的 Value 部分（即我们需要重构的起点）
        val attribute = targetTag.getAttribute("name") ?: return false
        val elementToRename = attribute.valueElement ?: return false // 这就是那个 XmlAttributeValue

        // 3. 执行 IDE 的 Rename 重构
        return try {
            WriteCommandAction.runWriteCommandAction(project) {
                val renameRefactoring = RefactoringFactory.getInstance(project)
                    .createRename(elementToRename, newKey)

                // 默认情况下，这会静默更新整个项目中的 R.string.oldKey
                renameRefactoring.run()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 获取或创建 strings.xml 的 PsiFile
     * 自动兼容多模块路径，如果找不到默认在 app 模块创建
     */
    fun getOrCreateStringsXmlPsiFile(): XmlFile? {
        val basePath = project.basePath ?: return null
        // 尝试几个常见的 Android 项目路径
        val commonPaths = listOf(
            "$basePath/app/src/main/res/values/strings.xml",
            "$basePath/src/main/res/values/strings.xml"
        )

        val psiManager = PsiManager.getInstance(project)

        for (path in commonPaths) {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(path)
            if (virtualFile != null) {
                return psiManager.findFile(virtualFile) as? XmlFile
            }
        }

        // 如果都不存在，则手动创建
        return createStringsXml(basePath)
    }

    private fun createStringsXml(basePath: String): XmlFile? {
        val resValuesPath = "$basePath/app/src/main/res/values"
        val directory = VfsUtil.createDirectories(resValuesPath)

        var xmlFile: XmlFile? = null
        WriteCommandAction.runWriteCommandAction(project) {
            val psiDirectory = PsiManager.getInstance(project).findDirectory(directory) ?: return@runWriteCommandAction
            val fileName = "strings.xml"
            val content = """<?xml version="1.0" encoding="utf-8"?>
<resources>
</resources>"""
            val file = psiDirectory.createFile(fileName)
            val document = PsiManager.getInstance(project).findFile(file.virtualFile)
            if (document is XmlFile) {
                // 初始化内容
                val factory = XmlElementFactory.getInstance(project)
                val root = factory.createTagFromText(content)
                document.rootTag?.replace(root)
                xmlFile = document
            }
        }
        return xmlFile
    }

    /**
     * 根据文本值查找 Key
     */
    fun findKeyByValue(value: String): String? {
        val xmlFile = getOrCreateStringsXmlPsiFile() ?: return null
        val rootTag = xmlFile.rootTag ?: return null

        return rootTag.findSubTags("string").find {
            it.value.text.trim() == value
        }?.getAttributeValue("name")
    }


    /**
     * Android String 特殊字符转义
     */
    private fun escapeAndroidString(value: String): String {
        return value.replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
