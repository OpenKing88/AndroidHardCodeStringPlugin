package com.openking.hardcodeplugin.processor

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.highlighting.HighlightManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.xml.XmlAttribute
import com.openking.hardcodeplugin.model.HardcodedItem
import com.openking.hardcodeplugin.model.RootHardcodedItem
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.io.File

object HardcodedStringReplacer {

    data class ReplacementResult(
        val totalLocations: Int,
        val successCount: Int,
        val failedCount: Int,
        val errors: List<String> = emptyList()
    )

    fun replaceAll(
        project: Project,
        items: List<RootHardcodedItem>,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit
    ): ReplacementResult {
        var successCount = 0
        val errors = mutableListOf<String>()
        val totalLocations = items.sumOf { it.items.size }
        var currentIndex = 0

        val replacedElements = mutableListOf<PsiElement>()
        val file = LocalFileSystem.getInstance().findFileByPath(items.first().path)?.let {
            PsiManager.getInstance(project).findFile(it)
        }
        ApplicationManager.getApplication().invokeAndWait {
            items.forEach { item ->
                val key = item.newKey
                WriteCommandAction.runWriteCommandAction(project) {
                    item.items.forEach { item ->
                        currentIndex++
                        val fileName = File(item.path).name
                        onProgress(currentIndex, totalLocations, fileName)

                        try {
                            val replaced = replaceSingle(project, item, key)
                            if (replaced != null) {
                                replacedElements.add(replaced)
                                successCount++
                            } else {
                                errors.add("$fileName:${item.lineNumber} - 替换失败")
                            }

                        } catch (e: Exception) {
                            errors.add("$fileName:${item.lineNumber} - ${e.message}")
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
        postProcessFile(file, replacedElements)
        return ReplacementResult(
            totalLocations = totalLocations,
            successCount = successCount,
            failedCount = totalLocations - successCount,
            errors = errors
        )
    }

    private fun replaceSingle(
        project: Project,
        item: HardcodedItem,
        realKey: String
    ): PsiElement? {
        val meta = item.expressionPtr ?: return null
        val element = meta.element ?: return null

        // 动态获取 R 包名
        val pkg = PackageNameResolver.resolveRPackageNameFromElement(element)
        if (pkg == null) {
            println("无法解析 R 包名: ${item.path}")
            return null
        }

        return when (item.type) {
            "XML" -> replaceXml(element, realKey)
            else -> replaceCode(project, element, item, realKey, pkg)
        }
    }


    /**
     * 替换 XML 中的硬编码
     */
    private fun replaceXml(element: PsiElement, key: String): PsiElement? {
        val attr = element as? XmlAttribute ?: return null
        attr.setValue("@string/$key")
        return attr.valueElement
    }

    /**
     * 替换代码中的硬编码
     */
    private fun replaceCode(
        project: Project,
        element: PsiElement,
        item: HardcodedItem,
        key: String,
        pkg: String
    ): PsiElement? {
        // 1. 构建表达式 (包含 FQN 全限定名)
        val expr = ReplaceExpressionBuilder.buildCodeExpr(pkg, key, item, element)
        // 2. 分语言处理替换和缩短引用
        val replaced = when (val file = element.containingFile) {
            is KtFile -> {
                val factory = KtPsiFactory(project)
                // 1. 手动添加 R 文件的 import (例如: import com.example.app.R)
                addImport(file, "$pkg.R")

                // 2. 如果是 Compose，手动添加 stringResource 的 import
                val isCompose = expr.contains("androidx.compose.ui.res.stringResource")
                if (isCompose) {
                    addImport(file, "androidx.compose.ui.res.stringResource")
                }
                // 3. 构建短表达式
                val shortExpr = buildShortExpression(key, isCompose, item.arguments)
                val newElement = factory.createExpression(shortExpr)

                element.replace(newElement)
            }

            else -> {
                // Java 专用缩短 (不依赖 Kotlin K2 服务，所以不会崩)
                val factory = PsiElementFactory.getInstance(project)
                element.replace(factory.createExpressionFromText(expr, element))
            }
        }

        return replaced
    }

    private fun buildShortExpression(key: String, isCompose: Boolean, args: List<String>): String {
        val resource = "R.string.$key"
        val arguments = if (args.isEmpty()) "" else ", ${args.joinToString(", ")}"

        return if (isCompose) {
            "stringResource($resource$arguments)"
        } else {
            "getString($resource$arguments)"
        }
    }

    private fun addImport(file: KtFile, fqName: String) {
        val importList = file.importList ?: return

        // 检查是否已存在
        val isAlreadyImported = importList.imports.any {
            val name = it.importedFqName?.asString()
            name == fqName || name == fqName.substringBeforeLast(".") + ".*"
        }
        if (isAlreadyImported) return

        val factory = KtPsiFactory(file.project)

        // 方式 B：最稳健的“全版本兼容”方式：通过字符串创建
        // 这完全避开了任何可能被移除的中间类（如 ImportPath）
        val dummyFile = factory.createFile("import $fqName")
        val importDirective = dummyFile.importDirectives.firstOrNull() ?: return

        // 插入 Import
        if (importList.imports.isEmpty()) {
            importList.add(importDirective)
        } else {
            // 插入到现有导入列表的末尾
            importList.add(importDirective)
        }
    }

    /**
     * 刷新IDE
     */
    private fun postProcessFile(file: PsiFile?, elements: List<PsiElement>) {
        file?:return
        val project = file.project
        val docManager = PsiDocumentManager.getInstance(project)
        val doc = docManager.getDocument(file) ?: return

        // 同步文档缓存
        ApplicationManager.getApplication().invokeAndWait {
            docManager.doPostponedOperationsAndUnblockDocument(doc)
            docManager.commitDocument(doc)
        }

        // 执行保存和高亮（非写 Action）
        ApplicationManager.getApplication().invokeLater {
            // 保存磁盘文件，触发 AS 重新生成 R 文件索引，消除爆红
            FileDocumentManager.getInstance().saveDocument(doc)

            // 批量格式化
            WriteCommandAction.runWriteCommandAction(project) {
                elements.forEach { if (it.isValid) CodeStyleManager.getInstance(project).reformat(it) }
            }

            // 刷新编辑器高亮
            DaemonCodeAnalyzer.getInstance(project).restart(file)
            highlightInAllEditors(project, file, elements)
        }
    }
    private fun highlightInAllEditors(project: Project, file: PsiFile, elements: List<PsiElement>) {
        val virtualFile = file.virtualFile ?: return
        // 获取所有打开了该文件的编辑器（解决 Tab 没选中就不高亮的问题）
        val editors = FileEditorManager.getInstance(project).getAllEditors(virtualFile)

        for (fileEditor in editors) {
            if (fileEditor is com.intellij.openapi.fileEditor.TextEditor) {
                val editor = fileEditor.editor
                val highlightManager = HighlightManager.getInstance(project)

                elements.forEach { element ->
                    if (element.isValid) {
                        highlightManager.addOccurrenceHighlight(
                            editor,
                            element.textRange.startOffset,
                            element.textRange.endOffset,
                            EditorColors.SEARCH_RESULT_ATTRIBUTES, // 系统搜索高亮颜色
                            HighlightManager.HIDE_BY_ANY_KEY, // 按任意键消失
                            null
                        )
                    }
                }
            }
        }
    }

    /**
     * 修改点高亮
     */
    private fun highlightElement(project: Project, element: PsiElement) {
        val editor: Editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        // 确保当前选中的编辑器确实是我们要高亮的那个文件
        if (editor.document != element.containingFile.viewProvider.document) return
        val highlightManager = HighlightManager.getInstance(project)
        highlightManager.addOccurrenceHighlight(
            editor,
            element.textRange.startOffset,
            element.textRange.endOffset,
            EditorColors.SEARCH_RESULT_ATTRIBUTES, // 这本身就是一个 TextAttributesKey
            HighlightManager.HIDE_BY_ANY_KEY,
            null
        )
    }

}