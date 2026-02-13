package com.openking.hardcodeplugin.processor

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.openking.hardcodeplugin.model.HardcodedItem
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.getParameterForArgument
import org.jetbrains.uast.toUElement

object ReplaceExpressionBuilder {

    fun buildCodeExpr(
        pkg: String,
        key: String,
        item: HardcodedItem,
        element: PsiElement
    ): String {
        val rRef = "$pkg.R.string.$key"

        return when (item.type) {
            "Kotlin" -> buildKotlinExpr(rRef, item, element)
            "Java" -> buildJavaExpr(rRef, item, element)
            else -> rRef
        }
    }

    private fun buildKotlinExpr(rRef: String, item: HardcodedItem, element: PsiElement): String {
        val isComposable = isInValidComposableContext(element)

        val args = if (item.arguments.isNotEmpty()) {
            ", " + item.arguments.joinToString(", ")
        } else ""

        return if (isComposable) {
            // Composable 作用域：使用 stringResource（全限定避免导入问题）
            "androidx.compose.ui.res.stringResource($rRef$args)"
        } else {
            // 非 Composable 作用域：使用传统 Context.getString
            val contextAccess = findBestContextAccess(element)
            val prefix = contextAccess.ifEmpty { "" }
            "${prefix}getString($rRef$args)"
        }
    }

    private fun buildJavaExpr(
        rRef: String,
        item: HardcodedItem,
        element: PsiElement
    ): String {
        val contextAccess = findBestContextAccess(element)
        val prefix = if (contextAccess.isEmpty()) "" else "$contextAccess."

        return if (item.arguments.isNotEmpty()) {
            val args = item.arguments.joinToString(", ")
            "${prefix}getString($rRef, $args)"
        } else {
            "${prefix}getString($rRef)"
        }
    }

    /** 判断当前字符串是否处于可安全调用 stringResource 的 Composable 作用域 */
    private fun isInValidComposableContext(element: PsiElement): Boolean {
        val boundary = PsiTreeUtil.getParentOfType(
            element,
            KtLambdaExpression::class.java,
            KtNamedFunction::class.java
        ) ?: return false

        return when (boundary) {
            is KtNamedFunction -> hasComposableAnnotation(boundary)
            is KtLambdaExpression -> isLambdaParameterComposable(boundary, element)
            else -> false
        }
    }

    /** 判断 lambda 参数位是否为 @Composable */
    private fun isLambdaParameterComposable(lambda: KtLambdaExpression, element: PsiElement): Boolean {
        // 1. 极少数情况：lambda 自身带有 @Composable 注解
        if (hasComposableAnnotation(lambda)) return true

        // 2. UAST 精确解析（最可靠）
        val argument = lambda.parent as? KtValueArgument
            ?: lambda.parent?.parent as? KtValueArgument
            ?: return false

        val callExpr = PsiTreeUtil.getParentOfType(argument, KtCallExpression::class.java) ?: return false
        val uCall = callExpr.toUElement() as? UCallExpression
        val method = uCall?.resolve()

        if (method != null) {
            val uLambda = lambda.toUElement() as? UCallExpression
            if (uLambda != null) {
                val parameter = uCall.getParameterForArgument(uLambda)
                if (parameter != null && parameter.annotations.any {
                        it.qualifiedName?.contains("Composable") == true
                    }) {
                    return true
                }
            }
        }

        // 3. 兜底启发式（resolve 失败时的保守判断）
        val argName = argument.getArgumentName()?.asName?.asString()

        // 明确非 Composable 的常见参数名
        val nonComposableParamNames = setOf(
            "onClick", "onLongClick", "onDoubleClick",
            "onValueChange", "onCheckedChange", "onDismissRequest",
            "key", "onGloballyPositioned", "onFocusChanged",
            "onSizeChanged", "onPlacementChanged",
            "onKeyEvent", "onPreviewKeyEvent"
        )

        if (argName != null && argName in nonComposableParamNames) {
            return false
        }

        // 常见尾随 lambda 为 Composable 的调用
        val composableTrailingCalls = setOf(
            "Box", "Column", "Row", "LazyColumn", "LazyRow", "LazyVerticalGrid",
            "Card", "Button", "IconButton", "TextField", "OutlinedTextField",
            "Scaffold", "Surface", "items", "item",
            "ModalBottomSheet", "DropdownMenu", "AlertDialog", "Center"
        )

        val callName = callExpr.calleeExpression?.text ?: ""
        val isTrailingLambda = argName == null && callExpr.valueArguments.lastOrNull() == argument

        val hasOuterComposable = findNearestComposableFunction(element) != null

        return when {
            isTrailingLambda && callName in composableTrailingCalls -> true
            hasOuterComposable -> true                     // 大多数其他 lambda 在 Composable 函数内都是 Composable
            else -> false
        }
    }

    private fun hasComposableAnnotation(function: KtFunction): Boolean {
        return function.annotationEntries.any { it.shortName?.asString() == "Composable" }
    }

    private fun hasComposableAnnotation(lambda: KtLambdaExpression): Boolean {
        return lambda.functionLiteral.annotationEntries.any { it.shortName?.asString() == "Composable" }
    }

    private fun findNearestComposableFunction(element: PsiElement): KtNamedFunction? {
        return PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, true)
            ?.takeIf { hasComposableAnnotation(it) }
    }

    /** 仅用于非 Composable 情况下的传统 Context 获取（不再使用 LocalContext） */
    private fun findBestContextAccess(element: PsiElement): String {
        val uElement = element.toUElement() ?: return "context."

        var current = uElement.uastParent
        while (current != null) {
            if (current is UClass) {
                // 查找名为 context/activity 的字段
                val contextField = current.fields.firstOrNull {
                    it.name.lowercase().contains("context") || it.name.lowercase().contains("activity")
                }
                if (contextField != null) {
                    return "${contextField.name}."
                }

                // 如果是 Activity 或 Fragment，使用隐式 this
                val qualifiedName = current.qualifiedName ?: ""
                if (qualifiedName.endsWith("Activity") || qualifiedName.endsWith("Fragment")) {
                    return ""
                }
            }
            current = current.uastParent
        }

        // 兜底（可根据项目实际情况修改）
        return "App.instance."
    }
}