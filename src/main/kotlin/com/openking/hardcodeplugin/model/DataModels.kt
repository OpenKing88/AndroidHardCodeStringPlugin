package com.openking.hardcodeplugin.model

import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer


data class RootHardcodedItem(
    val text: String,//字符串文本
    val path:String,
    var newKey: String,//自动生成的newKey
    val oldKey: String? = null,//strings.xml中对应字符串文本的key
    val items: MutableList<HardcodedItem> = mutableListOf(),
    var useNewKey: Boolean = true,
    var selected: Boolean = true //标记是否处理该项
)

data class HardcodedItem(
    val text: String,//字符串文本
    val path:String,
    val lineNumber: Int,
    val type: String, // "Kotlin", "Java", "XML"
    val arguments: List<String> = emptyList(), // 记录原始参数名，如 ["number", "user.id"]
    // 新增：用于精确定位源码位置
    val expressionPtr: SmartPsiElementPointer<PsiElement>? = null
)

