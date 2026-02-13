package com.openking.hardcodeplugin.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.openking.hardcodeplugin.scanner.HardCodeScanner
import com.openking.hardcodeplugin.ui.HardcodedResultDialog

class ScanHardcodedAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val scanner = HardCodeScanner()
        val items = scanner.scanAndBuildItems(project, psiFile)
        if (items.isEmpty()) {
            Messages.showInfoMessage(project, "未发现硬编码字符串", "扫描结果")
            return
        }
        HardcodedResultDialog(project, items).show()
    }

}
