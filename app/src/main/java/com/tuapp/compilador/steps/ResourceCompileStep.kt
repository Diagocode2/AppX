package com.tuapp.compilador.steps

import com.tuapp.compilador.core.BuildTools
import com.tuapp.compilador.core.ProcessRunner
import com.tuapp.compilador.model.ProjectModel
import com.tuapp.compilador.model.StepResult

/**
 * aapt2 compile: convierte cada recurso (XML de layout, values, drawables) a formato binario .flat
 * aapt2 link: junta todo + AndroidManifest.xml + android.jar y genera:
 *   - el APK base con los recursos empaquetados (resources.zip renombrado a base.apk)
 *   - R.java (necesario para que el código Kotlin/Java compile referenciando ids de recursos)
 */
class ResourceCompileStep(private val tools: BuildTools) {

    fun run(project: ProjectModel): StepResult {
        val flatDir = project.buildDir.resolve("flat").apply { mkdirs() }

        // 1) aapt2 compile --dir res -o flat/
        val compile = ProcessRunner.run(
            listOf(tools.aapt2.absolutePath, "compile", "--dir", project.resDir.absolutePath,
                "-o", flatDir.absolutePath),
            project.rootDir
        )
        if (compile.exitCode != 0) return StepResult.Failure("aapt2 compile falló", compile.output)

        val flatFiles = flatDir.listFiles()?.map { it.absolutePath } ?: emptyList()

        // 2) aapt2 link -> genera resources.zip + R.java
        val genSrcDir = project.buildDir.resolve("gen").apply { mkdirs() }
        val linkCmd = mutableListOf(
            tools.aapt2.absolutePath, "link",
            "-I", tools.androidJar.absolutePath,
            "--manifest", project.manifestFile.absolutePath,
            "--java", genSrcDir.absolutePath,
            "-o", project.compiledResZip.absolutePath,
            "--min-sdk-version", project.minSdk.toString(),
            "--target-sdk-version", project.targetSdk.toString()
        )
        linkCmd.addAll(flatFiles)

        val link = ProcessRunner.run(linkCmd, project.rootDir)
        if (link.exitCode != 0) return StepResult.Failure("aapt2 link falló", link.output)

        return StepResult.Success("Recursos compilados: ${project.compiledResZip.name}")
    }
}
