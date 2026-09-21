package org.treesitter.utils;

import org.treesitter.TSParser;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

public abstract class NativeUtils {
    private static final Set<String> loadedLibs = new HashSet<>();

    /** Set by the native image runtime and absent on the JVM. Read as a property so that
     *  this module keeps no compile-time dependency on any GraalVM artifact. */
    private static final String IMAGE_CODE_PROPERTY = "org.graalvm.nativeimage.imagecode";

    /** The only library whose JNI_OnLoad does anything worth driving. */
    private static final String CORE_LIB_NAME = "tree-sitter";

    private static final int JNI_VERSION_1_2 = 0x00010002;

    /** Implemented by static_init.c. Resolvable only in a native image. */
    private static native int initNative();
    private static String getFullLibName(String libName){
        String osName = System.getProperty("os.name").toLowerCase();
        String archName = System.getProperty("os.arch").toLowerCase();
        String ext;
        String os;
        String arch;
        if(osName.contains("windows")){
            ext = "dll";
            os = "windows";
        }else if(osName.contains("linux")){
            ext = "so";
            os = "linux-gnu";
        }else if(osName.contains("mac")){
            ext = "dylib";
            os = "macos";
        }else{
            throw new RuntimeException(String.format("Does not support OS: %s", osName));
        }
        if(archName.contains("amd64") || archName.contains("x86_64")){
            arch = "x86_64";
        }else if(archName.contains("aarch64")){
            arch = "aarch64";
        }else{
            throw new RuntimeException(String.format("Does not support arch: %s", archName));
        }
        String[] parts = libName.split("/");
        StringBuilder stringBuilder = new StringBuilder();
        for(int i = 0; i < parts.length; i++){
            if(i == parts.length - 1){
                stringBuilder.append(String.format("%s-%s-%s.%s", arch, os, parts[i], ext));
            }else{
                stringBuilder.append(parts[i]);
                stringBuilder.append("/");
            }
        }
        return stringBuilder.toString();
    }

    /**
     * Load native lib from class path by name convention. <br>
     *
     * This action is process safe and thread safe.
     *
     * <p>Name convention: <code>arch-os-name.ext</code>
     * <p><code>arch</code>
     * <ol>
     *     <li>x64: <code>x86_64</code></li>
     *     <li>arm64: <code>aarch64</code></li>
     * </ol>
     * @param libName Canonical name of the library. e.g. 'lib/foo', 'bar'
     */
    /**
     * Get the path of the native lib. If the lib is in the classpath, it will be
     * extracted to a temporary directory.
     *
     * @param libName Canonical name of the library. e.g. 'lib/foo', 'bar'
     * @return The path of the native lib.
     */
    public synchronized static Path libFile(String libName) {
        String fullLibName = getFullLibName(libName);

        // Check for user-defined library path
        String userDefinedPath = System.getProperty("tree-sitter-lib");
        if (userDefinedPath != null) {
            Path customPath = Path.of(userDefinedPath).resolve(fullLibName);
            if (Files.exists(customPath)) {
                return customPath;
            }
        }

        String prefix = fullLibName.replace("/", "_").replace("\\", "_");
        String suffix = fullLibName.contains(".") ?
                fullLibName.substring(fullLibName.lastIndexOf(".")) : ".tmp";

        try (InputStream inputStream = NativeUtils.class.getClassLoader().getResourceAsStream(fullLibName)) {
            if (inputStream == null) {
                throw new RuntimeException(String.format("Can't open %s", fullLibName));
            }
            Path tempFile = Files.createTempFile("tree-sitter-ng-" + prefix, suffix);
            tempFile.toFile().deleteOnExit();
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            return tempFile;
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract native library: " + fullLibName, e);
        }
    }

    /**
     * Load native lib from class path by name convention. <br>
     *
     * This action is process safe and thread safe.
     *
     * <p>Name convention: <code>arch-os-name.ext</code>
     * <p><code>arch</code>
     * <ol>
     *     <li>x64: <code>x86_64</code></li>
     *     <li>arm64: <code>aarch64</code></li>
     * </ol>
     * @param libName Canonical name of the library. e.g. 'lib/foo', 'bar'
     */
    public synchronized static void loadLib(String libName) {
        if (loadedLibs.contains(libName)) {
            return;
        }
        if (System.getProperty(IMAGE_CODE_PROPERTY) != null) {
            loadStatic(libName);
        } else {
            Path path = libFile(libName);
            System.load(path.toAbsolutePath().toString());
        }
        loadedLibs.add(libName);
    }

    /**
     * Resolves the library inside a native image, where it is a static archive linked into
     * the binary rather than a file to extract. Only System.loadLibrary finds a builtin
     * library, and GraalVM never calls JNI_OnLoad for a third-party one, so the core
     * library's initializer - which caches the JavaVM that ts_log needs - is driven
     * explicitly through initNative.
     */
    private static void loadStatic(String libName) {
        String simpleName = simpleLibName(libName);
        System.loadLibrary(simpleName);
        if (CORE_LIB_NAME.equals(simpleName)) {
            int version = initNative();
            if (version < JNI_VERSION_1_2) {
                throw new UnsatisfiedLinkError(
                        "tree-sitter JNI_OnLoad returned " + version
                                + ", so the JavaVM it caches is unset and any parser logger would crash");
            }
        }
    }

    /** The library name System.loadLibrary takes, from the resource-style name loadLib takes. */
    static String simpleLibName(String libName) {
        return libName.substring(libName.lastIndexOf('/') + 1);
    }
}
