import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.treesitter.build.Utils

/**
 * Builds a static archive per target, for consumers that link the parser into their own
 * binary instead of loading a shared library at runtime.
 *
 * Same sources, include dirs and flags as BuildNativeTask; only the output shape differs.
 * Unlike the shared libraries the archives are not committed: the release workflow builds
 * them and attaches them to the release, and jitpack.yml packages them from there.
 */
class BuildNativeStaticTask extends DefaultTask {

    @InputFiles
    FileCollection additionalCFiles = project.files()

    @InputFiles
    List<Directory> additionalIncludeDirs = []

    @InputFile
    RegularFile zigExe

    @Input
    List<String> getTargets() {
        def props = (String) project.rootProject.properties.get("treeSitterStaticTargets")
        if (props == null) {
            throw new GradleException("Can't find `treeSitterStaticTargets` in gradle.properties")
        }
        return props.split(",").collect { it.trim() }
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

    /**
     * The directory a target's archives land in. The glibc suffix is dropped so the layout
     * matches the `<arch>-<os>` prefix the shared libraries already use, and so consumers
     * can find their host's directory without knowing which glibc we built against.
     */
    static String targetDir(String target) {
        return target.replaceFirst(/\.\d+(\.\d+)*$/, "")
    }

    /**
     * Every statically linked JNI library defines JNI_OnLoad, and a consumer that links
     * more than one of them into a single binary - chatter links sqlite-jdbc's too - gets
     * a duplicate symbol at link time. Nothing calls it by that name here: GraalVM does
     * not invoke JNI_OnLoad for a third-party library, which is why static_init.c calls it
     * directly, and static_init.c is compiled with this same define. So renaming it is
     * both safe and the cheapest fix.
     */
    @Input
    String getOnLoadSymbol() {
        return "${libName.replace('-', '_')}_jni_on_load"
    }

    BuildNativeStaticTask() {
        description = "Build parser static archives"
        group = "build"
    }

    @TaskAction
    def buildNativeStatic() {
        def jniInclude = Utils.jniIncludeDir(project)
        def sources = []
        sources.addAll(jniCDir.asFileTree.matching { include("*.c") }.files)
        sources.addAll(srcDir.dir("src").asFileTree.matching {
            include("**/*.c"); include("**/*.cpp")
        }.files)
        sources.addAll(additionalCFiles.files)

        targets.each { target ->
            def objDir = project.layout.buildDirectory
                    .dir("static-objs/${targetDir(target)}").get().asFile
            project.delete(objDir)
            objDir.mkdirs()

            def includes = ["-I", srcDir, "-I", srcDir.dir("lib/include"),
                            "-I", jniInclude, "-I", Utils.jniMdInclude(project, target)]
            additionalIncludeDirs.each { includes.addAll(["-I", it]) }

            def objects = []
            sources.each { source ->
                def object = new File(objDir, "${source.name}.o")
                objects.add(object)
                // zig picks the language from the extension, so `.c` is compiled as C even
                // under `c++` - which is what the JNI glue needs, it does not compile as C++.
                def cmd = [zigExe, "c++", "-g0", "-fno-sanitize=undefined", "-c",
                           "-target", target, "-DJNI_OnLoad=${onLoadSymbol}".toString()]
                cmd.addAll(includes)
                cmd.addAll([source, "-o", object])
                project.exec { workingDir jniCDir; commandLine(cmd) }
            }

            def archiveDir = new File(outDir.asFile, targetDir(target))
            archiveDir.mkdirs()
            def archive = new File(archiveDir, "lib${libName}.a")
            project.delete(archive)
            def arCmd = [zigExe, "ar", "rcs", archive]
            arCmd.addAll(objects)
            project.exec { commandLine(arCmd) }
        }
    }
}
