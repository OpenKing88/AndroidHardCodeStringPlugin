package com.openking.hardcodeplugin.scanner

import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.openking.hardcodeplugin.model.HardcodedItem
import com.openking.hardcodeplugin.model.RootHardcodedItem
import org.jetbrains.kotlin.psi.*
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastVisitor
import java.util.*

class HardCodeScanner {

    fun scanAndBuildItems(project: Project, psiFile: PsiFile): List<RootHardcodedItem> {
        val strItems = when (psiFile.virtualFile.extension) {
            "kt", "java" -> scanSingleFile(psiFile)
            "xml" -> scanXmlFile(psiFile)
            else -> emptyList()
        }

        val xmlManager = StringXmlManager(project)

        return strItems.groupBy { it.text }.map { item ->
            val oldKey = xmlManager.findKeyByValue(item.key)
            val newKey = generateStringKey(project, item.key)

            RootHardcodedItem(
                text = item.key,
                path = item.value.first().path,
                newKey = newKey,
                oldKey = oldKey,
                useNewKey = oldKey.isNullOrEmpty(),
                items = item.value.toMutableList()
            )
        }
    }

    private fun scanXmlFile(psiFile: PsiFile): List<HardcodedItem> {
        val results = mutableListOf<HardcodedItem>()

        if (psiFile !is XmlFile) return results

        val rootTag = psiFile.rootTag ?: return results

        processXmlTag(rootTag, psiFile, results)

        return results
    }

    private fun processXmlTag(tag: XmlTag, psiFile: PsiFile, results: MutableList<HardcodedItem>) {
        arrayListOf(
            "android:text", "android:label", "android:contentDescription"
        ).forEach { attr ->
            val textAttr = tag.getAttribute(attr)
            if (textAttr != null) {
                val value = textAttr.value
                if (value != null && !value.startsWith("@string/") && value.isNotBlank()) {
                    val lineNumber = getXmlLineNumber(psiFile, textAttr)
                    val item = HardcodedItem(
                        text = value,
                        path = psiFile.virtualFile.path,
                        lineNumber = lineNumber,
                        type = "XML",
                        arguments = emptyList(),
                        expressionPtr = SmartPointerManager.createPointer(textAttr)
                    )
                    results.add(item)
                }
            }
        }

        tag.subTags.forEach { subTag ->
            processXmlTag(subTag, psiFile, results)
        }
    }

    private fun getXmlLineNumber(psiFile: PsiFile, element: PsiElement): Int {
        val document = PsiDocumentManager.getInstance(psiFile.project)
            .getDocument(psiFile) ?: return -1
        return document.getLineNumber(element.textOffset) + 1
    }

    fun scanSingleFile(psiFile: PsiFile): List<HardcodedItem> {
        val results = mutableListOf<HardcodedItem>()

        val uFile = psiFile.toUElement() as? UFile ?: return emptyList()

        uFile.accept(object : AbstractUastVisitor() {
            /**
             * 处理 Kotlin 字符串模板 (例如: "66|$number")
             */
            override fun visitExpression(node: UExpression): Boolean {
                val sourcePsi = node.sourcePsi
                // 检查是否是 Kotlin 的字符串模板表达式
                if (sourcePsi is KtStringTemplateExpression) {
                    val entries = sourcePsi.entries
                    val args = mutableListOf<String>()

                    // 如果 entries > 1，说明里面包含变量（例如 "66|" 是一个 entry，$number 是另一个）
                    if (entries.size > 1) {
                        val sb = StringBuilder()
                        var placeholderIndex = 1
                        var hasStaticText = false

                        for (entry in entries) {
                            when (entry) {
                                is KtLiteralStringTemplateEntry -> {
                                    // 静态文本部分
                                    val text = entry.text
                                    sb.append(text)
                                    if (text.isNotBlank()) hasStaticText = true
                                }

                                is KtSimpleNameStringTemplateEntry, is KtBlockStringTemplateEntry -> {
                                    // 变量部分 ($number 或 ${user.name})，替换为 %n$s
                                    args.add(entry.expression?.text ?: "")
                                    sb.append("%$placeholderIndex\$s")
                                    placeholderIndex++
                                }
                            }
                        }

                        val finalString = sb.toString()
                        if (!HardcodedSkipPolicy.shouldSkip(sb.toString(), psiFile)) {
                            println("---------------处理 Kotlin 字符串模板的字符串-----------$finalString")
                            results.add(
                                createModel(
                                    psiFile,
                                    node,
                                    finalString,
                                    args
                                )
                            )
                        }
                        return true // 拦截，防止 visitLiteralExpression 再次处理内部片段
                    }
                }
                return super.visitExpression(node)
            }

            /**
             * 处理 Java 字符串拼接 (例如: "66|" + number)
             */
            override fun visitPolyadicExpression(node: UPolyadicExpression): Boolean {
                // Java 没有 String Template，它用 PolyadicExpression 表示 "A" + b + "C"
                if (node.operator.toString().contains("+")) {
                    val sb = StringBuilder()
                    var placeholderIndex = 1
                    var hasLiteral = false
                    val args = mutableListOf<String>()

                    node.operands.forEach { operand ->
                        if (operand is ULiteralExpression && operand.value is String) {
                            sb.append(operand.value)
                            hasLiteral = true
                        } else {
                            args.add(operand.sourcePsi?.text ?: "")
                            sb.append("%$placeholderIndex\$s")
                            placeholderIndex++
                        }
                    }
                    val value = sb.toString()
                    if (!HardcodedSkipPolicy.shouldSkip(value, psiFile) && hasLiteral) {
                        println("---------------处理处理 Java 字符串拼接的字符串-----------$value")
                        results.add(
                            createModel(
                                psiFile,
                                node,
                                value,
                                args
                            )
                        )
                    }
                    return true
                }
                return super.visitPolyadicExpression(node)
            }

            /**
             * 处理正常的字符
             */
            override fun visitLiteralExpression(node: ULiteralExpression): Boolean {
                val value = node.value
                val parent = node.uastParent
                if (parent?.sourcePsi is KtStringTemplateExpression || parent is UPolyadicExpression) {
                    println("-------KtStringTemplateExpression||UPolyadicExpression--------处理的字符串-----------$value")
                    return super.visitLiteralExpression(node)
                }
                if (value is String && value.isNotBlank() && !HardcodedSkipPolicy.shouldSkip(value, psiFile)) {
                    println("---------------处理正常的字符串-----------$value")
                    results.add(createModel(psiFile, node, value))
                }
                return super.visitLiteralExpression(node)
            }
        })

        return results.distinct()
    }

    private fun createModel(
        psiFile: PsiFile,
        node: UElement,
        text: String,
        args: List<String> = emptyList()
    ): HardcodedItem {
        val pointer = SmartPointerManager.getInstance(psiFile.project).createSmartPsiElementPointer(node.sourcePsi!!)
        return HardcodedItem(
            text = text,
            path = psiFile.virtualFile.path,
            lineNumber = getLineNumber(psiFile, node),
            type = if (psiFile is KtFile) "Kotlin" else "Java",
            arguments = args,
            expressionPtr = pointer
        )
    }

    private fun getLineNumber(psiFile: PsiFile, element: UElement): Int {
        val sourcePsi = element.sourcePsi ?: return -1
        val document = PsiDocumentManager.getInstance(psiFile.project)
            .getDocument(psiFile) ?: return -1
        return document.getLineNumber(sourcePsi.textOffset) + 1
    }

    /**
     * 检查 key是否已经存在strings.xml
     */
    fun isKeyExistsInAndroidResources(project: Project, key: String): Boolean {
        val scope = GlobalSearchScope.projectScope(project)
        // 我们手动遍历或者使用更简单的：在 strings.xml 环境下查找 XmlTag
        // 这里使用更直观的 PSI 搜索
        val psiManager = PsiManager.getInstance(project)

        // 查找所有 <string> 标签且其 name 属性等于 key
        // 注意：这里为了严谨，我们先过滤出 values 目录
        val files = FileTypeIndex.getFiles(XmlFileType.INSTANCE, scope)

        for (vf in files) {
            if (!vf.path.contains("/res/values")) continue
            if (!vf.name.startsWith("strings")) continue

            val psiFile = psiManager.findFile(vf) as? XmlFile ?: continue
            val root = psiFile.rootTag ?: continue

            // 使用针对 XML 优化的子标签查找
            val tags = root.findSubTags("string")
            for (tag in tags) {
                if (tag.getAttributeValue("name") == key) {
                    return true
                }
            }
        }

        return false
    }


    /**
     * 自动生成 key
     * 中文 → 转拼音 or hash
     * 英文 → 小写 + 下划线
     *
     */
    fun generateStringKey(project: Project, text: String, selected: Boolean = true): String {
        // 使用文本的哈希值作为随机种子，确保相同文本生成相同Key
        val seed = text.hashCode()
        val random = if (selected) {
            Random()
        } else {
            Random(seed.toLong())
        }

        // 生成前缀 (1-4字符)
        val prefixLength = (random.nextInt(4) + 1).coerceAtMost(4)
        val prefix = StringBuilder()

        // 第一位必须是小写字母
        val firstLetter = ('a'..'z').toList()[random.nextInt(26)]
        prefix.append(firstLetter)

        // 生成前缀的其余字符
        for (i in 2..prefixLength) {
            val validChars = mutableListOf<Char>()
            validChars.addAll('a'..'z')
            validChars.addAll('0'..'9')

            // 如果上一个字符不是下划线，则可以添加下划线
            if (prefix.last() != '_') {
                validChars.add('_')
            }

            val nextChar = validChars[random.nextInt(validChars.size)]
            prefix.append(nextChar)
        }

        // 确保前缀最后一位不是下划线
        while (prefix.last() == '_') {
            val replacementOptions = ('a'..'z').toList() + ('0'..'9').toList()
            prefix.setCharAt(prefix.length - 1, replacementOptions[random.nextInt(replacementOptions.size)])
        }

        // 生成后缀 (2-8字符)
        val suffixLength = random.nextInt(7) + 2
        val suffix = StringBuilder()

        for (i in 1..suffixLength) {
            val isLetter = random.nextBoolean()
            suffix.append(
                if (isLetter) {
                    ('a'..'z').toList()[random.nextInt(26)]
                } else {
                    ('0'..'9').toList()[random.nextInt(10)]
                }
            )
        }
        return if (isKeyExistsInAndroidResources(project = project, "${prefix}_${suffix}")) {
            generateStringKey(project, text)
        } else {
            "${prefix}_${suffix}"
        }
    }
}