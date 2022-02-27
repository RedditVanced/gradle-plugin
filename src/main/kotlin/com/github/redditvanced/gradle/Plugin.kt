package com.github.redditvanced.gradle

import com.android.build.gradle.tasks.ProcessLibraryManifest
import com.github.redditvanced.gradle.models.CoreManifest
import com.github.redditvanced.gradle.models.PluginManifest
import com.github.redditvanced.gradle.task.*
import groovy.json.JsonBuilder
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.AbstractCopyTask
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.compile.AbstractCompile

private const val TASK_GROUP = "reddit vanced"

@Suppress("unused")
abstract class Plugin : Plugin<Project> {
	override fun apply(project: Project) {
		project.extensions.create("redditVanced", RedditVancedExtension::class.java, project)
		val extension = project.extensions.getRedditVanced()

		configureRedditConfiguration(project)

		project.afterEvaluate {
			if (extension.projectType.get() != ProjectType.REGULAR)
				configureTasks(project)
		}
	}

	private fun configureTasks(project: Project) {
		val intermediates = project.buildDir.resolve("intermediates")
		val android = project.extensions.getAndroid()
		val extension = project.extensions.getRedditVanced()
		val projectType = extension.projectType.get()

		project.tasks.register("compileResources", CompileResourcesTask::class.java) { task ->
			task.group = TASK_GROUP

			val processManifestTask = project.tasks.getByName("processDebugManifest") as ProcessLibraryManifest
			task.dependsOn(processManifestTask)

			task.input.set(android.sourceSets.getByName("main").res.srcDirs.single())
			task.manifestFile.set(processManifestTask.manifestOutputFile)

			task.outputFile.set(intermediates.resolve("res.apk"))

			task.doLast { _ ->
				val resApkFile = task.outputFile.asFile.get()

				if (resApkFile.exists()) {
					project.tasks.named("make", AbstractCopyTask::class.java) {
						it.from(project.zipTree(resApkFile)) { copySpec ->
							copySpec.exclude("AndroidManifest.xml")
						}
					}
				}
			}
		}

		project.tasks.register("compileDex", CompileDexTask::class.java) { task ->
			task.group = TASK_GROUP

			task.outputFile.set(intermediates.resolve("classes.dex"))

			for (taskName in listOf("compileDebugJavaWithJavac", "compileDebugKotlin")) {
				val compileTask = project.tasks.getByName(taskName) as AbstractCompile
				task.input.from(compileTask.destinationDirectory)
				task.dependsOn(compileTask)
			}
		}

		project.tasks.register("genSources", GenSourcesTask::class.java) { task ->
			task.group = TASK_GROUP
		}

		val makeTaskType = when (projectType) {
			ProjectType.INJECTOR -> Copy::class.java
			else -> Zip::class.java
		}

		project.tasks.register("make", makeTaskType) { task ->
			task.group = TASK_GROUP

			val compileDexTask = project.tasks.getByName("compileDex") as CompileDexTask
			task.dependsOn(compileDexTask)

			if (projectType == ProjectType.PLUGIN || projectType == ProjectType.CORE) {
				val manifestFile = intermediates.resolve("manifest.json")

				task.from(manifestFile)
				task.doFirst {
					require(project.version != "unspecified") { "No project version is set!" }

					val manifest: Any = if (projectType == ProjectType.PLUGIN) {
						PluginManifest(
							name = project.name,
							version = project.version.toString(),
							pluginClass = extension.pluginClass.get(),
							changelog = extension.changelog.getOrElse(""),
							description = project.description ?: "",
							authors = extension.authors.get(),
							customUpdaterUrl = extension.customUpdaterUrl.orNull,
							loadResources = extension.loadResources.getOrElse(false),
							requiresRestart = extension.requiresRestart.getOrElse(false),
						)
					} else {
						CoreManifest(
							version = project.version.toString(),
							redditVersionCode = extension.projectRedditVersion.get()
						)
					}
					manifestFile.writeText(JsonBuilder(manifest).toString())
				}
			}

			task.from(compileDexTask.outputFile)

			if (projectType == ProjectType.INJECTOR) {
				task.into(project.buildDir)
				task.rename { "Injector.dex" }

				// Retarded gradle won't remove the target dir as an output
				// Absolutely fuming
				task.outputs.file("${project.buildDir}/Injector.dex")
			} else {
				task as Zip

				task.dependsOn(project.tasks.getByName("compileResources"))
				task.isPreserveFileTimestamps = false
				task.archiveBaseName.set(project.name)
				task.archiveVersion.set("")
				task.destinationDirectory.set(project.buildDir)
			}

			task.doLast {
				task.logger.lifecycle("Made package at ${task.outputs.files.first().absolutePath}")
			}
		}

		project.tasks.register("deployWithAdb", DeployWithAdbTask::class.java) { task ->
			task.group = TASK_GROUP
			task.dependsOn("make")
		}

		project.tasks.register("uninstallWithAdb", UninstallWithAdbTask::class.java) { task ->
			task.group = TASK_GROUP
			task.dependsOn("make")
		}

		project.tasks.register("requestPublishPlugin", RequestPublishPluginTask::class.java) { task ->
			task.group = TASK_GROUP
			task.enabled = extension.projectType.get() == ProjectType.PLUGIN
		}
	}
}
