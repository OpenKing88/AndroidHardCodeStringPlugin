package com.openking.hardcodeplugin.processor

import com.intellij.psi.PsiElement
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import org.jetbrains.kotlin.psi.KtElement

object ShortenApiCompact {

    fun shorten(element: PsiElement) {
        if (element !is KtElement) {
            // Java 逻辑：直接使用通用 API，非常稳定
            JavaCodeStyleManager.getInstance(element.project).shortenClassReferences(element)
            return
        }

        // --- Kotlin 逻辑开始 ---

        // 1. 尝试使用 K2 API (Android Studio Ladybug+ / Koala 预览版 且开启 K2)
        if (tryK2Shorten(element)) return

        // 2. 尝试使用 K1 API (旧版本 AS 或 关闭 K2 的环境)
        if (tryK1Shorten(element)) return

        // 3. 最终兜底：使用 Java 缩短器处理 Kotlin (能处理大部分 FQN 情况)
        JavaCodeStyleManager.getInstance(element.project).shortenClassReferences(element)
    }

    private fun tryK2Shorten(element: KtElement): Boolean {
        return try {
            val clazz = Class.forName("org.jetbrains.kotlin.idea.base.codeInsight.ShortenReferencesFacility")
            val getInstance = clazz.getMethod("getInstance")
            val shorten = clazz.getMethod("shorten", KtElement::class.java)
            
            val facility = getInstance.invoke(null)
            shorten.invoke(facility, element)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun tryK1Shorten(element: KtElement): Boolean {
        return try {
            val clazz = Class.forName("org.jetbrains.kotlin.idea.core.ShortenReferences")
            val defaultField = clazz.getField("DEFAULT")
            val shorten = clazz.getMethod("process", KtElement::class.java)
            
            val defaultInstance = defaultField.get(null)
            shorten.invoke(defaultInstance, element)
            true
        } catch (e: Exception) {
            false
        }
    }
}