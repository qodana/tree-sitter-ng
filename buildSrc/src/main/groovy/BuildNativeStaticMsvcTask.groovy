import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.treesitter.build.Utils

/**
 * The Windows half of BuildNativeStaticTask.
 *
 * zig cannot produce this one: it defaults to the mingw-w64 ABI and cannot redistribute
 * the MSVC CRT or the Windows SDK, while native-image on Windows links through MSVC. So
 * the same sources go through cl.exe and lib.exe on a Windows runner, which is expected to
 * have them on PATH through a developer command prompt.
 */
class BuildNativeStaticMsvcTask extends DefaultTask {

    @InputFiles
    FileCollection additionalCFiles = project.files()

    @InputFiles
    List<Directory> additionalIncludeDirs = []

    @Input
    String getTarget() {
        return project.rootProject.properties.get("treeSitterStaticMsvcTarget")
    }

    @Input
    String getLibVersion() {
        return project.property("libVersion")
    }

    @Input
    String getLibName() {
        return project.name
    }

    @InputDirectory
    Directory getSrcDir() {
        return project.layout.buildDirectory.dir("$libName/$libName-$libVersion").get()
    }

    @Internal
    Directory getJniCDir() {
        return Utils.jniSrcDir(project)
    }

    @InputFiles
    FileCollection getJniSourceFiles() {
        return jniCDir.asFileTree.matching { include("*.c"); include("*.h") }
    }

    @InputFiles
    FileCollection getParserSourceFiles() {
        return srcDir.dir("src").asFileTree.matching {
            include("**/*.c"); include("**/*.h"); include("**/*.cpp")
        }
    }

    @OutputDirectory
    Directory getOutDir() {
        return project.layout.buildDirectory.dir("static-libs").get()
    }

    BuildNativeStaticMsvcTask() {
        description = "Build parser static archives with MSVC"
        group = "build"
    }

    @TaskAction
    def buildNativeStaticMsvc() {
        def jniInclude = Utils.jniIncludeDir(project)
        def objDir = project.layout.buildDirectory.dir("static-objs/$target").get().asFile
        project.delete(objDir)
        objDir.mkdirs()

        def sources = []
        sources.addAll(jniCDir.asFileTree.matching { include("*.c") }.files)
        sources.addAll(srcDir.dir("src").asFileTree.matching {
            include("**/*.c"); include("**/*.cpp")
        }.files)
        sources.addAll(additionalCFiles.files)

        def includes = ["/I", srcDir, "/I", srcDir.dir("lib/include"),
                        "/I", jniInclude, "/I", jniInclude.dir("win32")]
        additionalIncludeDirs.each { includes.addAll(["/I", it]) }

        def objects = []
        sources.each { source ->
            def object = new File(objDir, "${source.name}.obj")
            objects.add(object)
            def cmd = ["cl.exe", "/nologo", "/c", "/O2", "/MD"]
            cmd.addAll(includes)
            cmd.addAll([source, "/Fo$object"])
            project.exec { workingDir jniCDir; commandLine(cmd) }
        }

        def archiveDir = new File(outDir.asFile, target)
        archiveDir.mkdirs()
        def archive = new File(archiveDir, "${libName}.lib")
        project.delete(archive)
        def libCmd = ["lib.exe", "/nologo", "/OUT:$archive"]
        libCmd.addAll(objects)
        project.exec { commandLine(libCmd) }
    }
}
