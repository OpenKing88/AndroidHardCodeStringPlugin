package com.openking.hardcodeplugin.processor

import com.android.tools.idea.model.AndroidModel
import com.android.tools.idea.projectsystem.getModuleSystem
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.kotlin.psi.KtFile

object PackageNameResolver {

    private val packageCache = mutableMapOf<String, String?>()

    /**
     * 获取 R 类的完整包名
     */
    fun resolveRPackageName(project: Project, psiFile: PsiFile): String? {
        val cacheKey = psiFile.virtualFile?.path ?: return null

        // 使用缓存
        if (packageCache.containsKey(cacheKey) && !packageCache[cacheKey].isNullOrEmpty()) {
            return packageCache[cacheKey]
        }

        val result = resolveRPackageNameInternal(project, psiFile)
        packageCache[cacheKey] = result
        return result
    }

    fun getRPackageName(module: Module): String? {
        // 这是目前官方推荐的获取模块 PackageName (R 类包名) 的方式
        // 它会自动处理 namespace 和 manifest 中的 package
        return module.getModuleSystem().getPackageName()
    }

    private fun resolveRPackageNameInternal(project: Project, psiFile: PsiFile): String? {
        // 1. 从模块获取
        val module = ModuleUtil.findModuleForFile(psiFile.virtualFile, project)
        if (module != null) {
            val pN = getRPackageName(module)
            if (!pN.isNullOrEmpty()) return pN

            // ✅ 尝试从 AndroidManifest.xml 读取
            val manifestPackage = getPackageFromManifestFile(project, module)
            if (manifestPackage != null && verifyRClassExists(project, manifestPackage)) {
                return manifestPackage
            }

            // ✅ 从 build.gradle 读取 namespace
            val namespaceFromGradle = getNamespaceFromGradle(module)
            if (namespaceFromGradle != null && verifyRClassExists(project, namespaceFromGradle)) {
                return namespaceFromGradle
            }

            // ✅ 尝试从 AndroidModel 获取
            val facet = AndroidFacet.getInstance(module)
            if (facet != null) {
                val model = AndroidModel.get(facet)
                if (model != null) {
                    val applicationId = model.applicationId
                    if (applicationId.isNotEmpty() &&
                        applicationId != "DISABLED" &&
                        verifyRClassExists(project, applicationId)
                    ) {
                        return applicationId
                    }
                }
            }
        }

        // 2. 从文件的 package 声明推断
        val filePackage = when (psiFile) {
            is KtFile -> psiFile.packageFqName.asString()
            else -> (psiFile as? PsiJavaFile)?.packageName
        }

        if (!filePackage.isNullOrEmpty()) {
            val possiblePackages = generatePossibleRPackages(filePackage)
            for (pkg in possiblePackages) {
                if (verifyRClassExists(project, pkg)) {
                    return pkg
                }
            }
        }

        // 3. 搜索整个项目中的 R 类
        return findRPackageInProject(project)
    }

    /**
     * ✅ 从 AndroidManifest.xml 文件直接读取 package 属性
     */
    private fun getPackageFromManifestFile(
        project: Project,
        module: Module
    ): String? {
        try {
            val moduleDir = module.moduleFile?.parent ?: return null

            // 查找 AndroidManifest.xml 的可能位置
            val manifestPaths = listOf(
                "src/main/AndroidManifest.xml",
                "AndroidManifest.xml"
            )

            for (path in manifestPaths) {
                val manifestFile = moduleDir.findFileByRelativePath(path) ?: continue
                val psiFile = PsiManager.getInstance(project).findFile(manifestFile) as? XmlFile ?: continue

                // 读取 <manifest package="xxx"> 中的 package 属性
                val rootTag = psiFile.rootTag
                if (rootTag?.name == "manifest") {
                    val packageName = rootTag.getAttributeValue("package")
                    if (!packageName.isNullOrEmpty() && packageName != "DISABLED") {
                        return packageName
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * ✅ 从 build.gradle/build.gradle.kts 读取 namespace
     */
    private fun getNamespaceFromGradle(module: Module): String? {
        try {
            val moduleDir = module.moduleFile?.parent ?: return null

            // 查找 build.gradle 或 build.gradle.kts
            val gradleFiles = listOf("build.gradle", "build.gradle.kts")

            for (fileName in gradleFiles) {
                val gradleFile = moduleDir.findChild(fileName) ?: continue
                val content = String(gradleFile.contentsToByteArray())

                // 1. 匹配 namespace
                val namespacePatterns = listOf(
                    Regex("""namespace\s*=\s*["']([^"']+)["']"""),
                    Regex("""namespace\s+["']([^"']+)["']"""),
                    Regex("""namespace\s*\(\s*["']([^"']+)["']\s*\)""")
                )

                for (pattern in namespacePatterns) {
                    val match = pattern.find(content)
                    if (match != null) {
                        val namespace = match.groupValues[1]
                        if (namespace != "DISABLED") {
                            return namespace
                        }
                    }
                }

                // 2. 如果没有 namespace，尝试读取 applicationId
                val appIdPatterns = listOf(
                    Regex("""applicationId\s*=\s*["']([^"']+)["']"""),
                    Regex("""applicationId\s+["']([^"']+)["']"""),
                    Regex("""applicationId\s*\(\s*["']([^"']+)["']\s*\)""")
                )

                for (pattern in appIdPatterns) {
                    val match = pattern.find(content)
                    if (match != null) {
                        val appId = match.groupValues[1]
                        if (appId != "DISABLED") {
                            return appId
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * ✅ 验证 R 类是否真实存在
     */
    private fun verifyRClassExists(project: Project, packageName: String): Boolean {
        if (packageName.isEmpty() || packageName == "DISABLED") {
            return false
        }

        val rClassName = "$packageName.R"
        val javaPsiFacade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)

        try {
            val rClass = javaPsiFacade.findClass(rClassName, scope)
            if (rClass != null) {
                // 进一步验证：检查是否有 string 内部类
                val stringClass = rClass.findInnerClassByName("string", false)
                return stringClass != null
            }
        } catch (e: Exception) {
            // 忽略异常
        }

        return false
    }

    /**
     * 根据文件包名生成可能的 R 类包名
     */
    private fun generatePossibleRPackages(filePackage: String): List<String> {
        val parts = filePackage.split(".")
        val packages = mutableListOf<String>()

        // 从完整包名开始，逐级向上
        for (i in parts.size downTo 2) {
            val pkg = parts.take(i).joinToString(".")
            if (pkg != "DISABLED") {
                packages.add(pkg)
            }
        }

        return packages
    }

    /**
     * 在整个项目中搜索 R 类
     */
    private fun findRPackageInProject(project: Project): String? {
        val javaPsiFacade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)

        try {
            // 搜索所有名为 R 的类
            val rClasses = javaPsiFacade.findClasses("R", scope)

            // 过滤出 Android 的 R 类（必须有 string 内部类）
            val validRClasses = rClasses
                .filter { rClass ->
                    rClass.findInnerClassByName("string", false) != null
                }
                .mapNotNull { it.qualifiedName }
                .filter { it.endsWith(".R") }
                .map { it.removeSuffix(".R") }
                .filter { it != "DISABLED" }

            // 返回最短的包名（通常是主模块）
            return validRClasses.minByOrNull { it.length }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }

    /**
     * 从 PSI 元素获取 R 包名
     */
    fun resolveRPackageNameFromElement(element: PsiElement): String? {
        return resolveRPackageName(element.project, element.containingFile)
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        packageCache.clear()
    }
}