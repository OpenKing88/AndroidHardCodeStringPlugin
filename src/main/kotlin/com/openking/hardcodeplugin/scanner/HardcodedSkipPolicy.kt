package com.openking.hardcodeplugin.scanner

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UDeclaration
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.toUElement

/**
 * 字符串硬编码扫描结果跳过规则
 *
 */
object HardcodedSkipPolicy {

    /**
     * 判断是否应该跳过
     */
    fun shouldSkip(text: String, psi: PsiElement?): Boolean {
        val trimmed = text.trim()

        // 1. 基础空校验
        if (trimmed.isEmpty()) return true

        // 2. 长度校验
        if (trimmed.length <= 1) return true

        // 3. 内容校验 (修复了泰语/中文支持)
        if (isTechnicalContent(trimmed)) return true

        // 4. 环境校验 (测试文件夹)
        if (isInTestFile(psi)) return true

        // 5. 上下文校验 (Log, Intent, Tag, 注解等)
        if (isTechnicalContext(psi)) return true


        return false
    }

    private fun isTechnicalContent(text: String): Boolean {
        // A. 剔除占位符
        val noPlaceholder = text.replace(Regex("%(\\d\\$)?[-#+ 0,(\\<]*[\\d\\.]*[a-zA-Z]"), "")

        // B. 必须包含至少一个字母（支持全语种）
        // 如果剔除占位符后不包含字母，则是纯技术符号，跳过
        if (!noPlaceholder.any { Character.isLetter(it) }) return true

        // C. 颜色值正则 (支持 #RRGGBB 或 #AARRGGBB)
        if (Regex("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})$").matches(text)) return true

        // D. 常见的技术前缀
        val lower = text.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("android:") || lower.contains("://")) return true

        return false
    }

    /**
     * 环境校验：跳过 test 和 androidTest 目录下的文件
     */
    private fun isInTestFile(psi: PsiElement?): Boolean {
        val path = psi?.containingFile?.virtualFile?.path ?: return false
        return path.contains("/test/") || path.contains("/androidTest/")
    }

    /**
     * 上下文校验：通过 UAST 判断字符串是否被用于非 UI 场景
     */
    private fun isTechnicalContext(psi: PsiElement?): Boolean {
        if (psi == null) return false

        // A. 过滤注解内部的内容 (Room, Retrofit, Gson 等)
        // 如: @SerializedName("user_id"), @ColumnInfo(name = "id"), @GET("api/path")
        if (PsiTreeUtil.getParentOfType(psi, PsiAnnotation::class.java) != null ||
            PsiTreeUtil.getParentOfType(psi, KtAnnotationEntry::class.java) != null) return true

        // B. 过滤技术性方法的参数
        val uElement = psi.toUElement()
        // 向上寻找最近的调用表达式
        val call = uElement?.getParentOfType(
            UCallExpression::class.java, false, UExpression::class.java, UDeclaration::class.java
        ) ?: return false

        val methodName = call.methodName?.lowercase() ?: ""
        
        // 常见的非 UI 方法名（这些方法里的字符串通常是 Key、Tag、路径等）
        val technicalMethods = setOf(
            // Log & Debug
            "d", "e", "i", "v", "w", "wtf", "log", "print", "println", "error", "tag",
            // Intent & Bundle
            "putextra", "getstringextra", "putstring", "getstring", "putlong", "putint",
            // SharedPreferences
            "getboolean", "getint", "getfloat", "edit",
            // View Tags
            "settag", "gettag", "findviewwithtag",
            // 数据库 & SQL
            "query", "insert", "update", "delete", "execsql", "columnindex",
            // Android 架构/系统
            "action", "addcategory", "setpackage", "setclassname", "getsystemservice",
            // Compose 测试相关
            "testtag", "semantics"
        )

        if (technicalMethods.contains(methodName)) return true

        // C. 过滤特定类的调用 (如 Log.i, Timber.e, BuildConfig.XXX)
        val receiverType = call.receiverType?.canonicalText ?: ""
        if (receiverType.contains("android.util.Log") || 
            receiverType.contains("timber.log.Timber") ||
            receiverType.contains("BuildConfig")) return true

        return false
    }
}