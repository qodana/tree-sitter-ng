/*
 * Drives JNI_OnLoad explicitly for a statically linked build.
 *
 * GraalVM calls JNI_OnLoad_<lib> only for the JDK libraries named in a hardcoded list in
 * com.oracle.svm.core.jni.JNILibraryInitializer#fillCGlobalDataMap, so a third-party
 * static library is never initialised and the JavaVM that org_treesitter_TSParser.c
 * caches stays null. ts_log dereferences it on its first statement, so a parser with a
 * logger attached would crash.
 *
 * Ordinary Java_* symbols do resolve for statically linked libraries, so this exposes one
 * and calls JNI_OnLoad from it. Called once from NativeUtils.loadLib, after
 * System.loadLibrary has registered the builtin library.
 *
 * BuildNativeStaticTask compiles this file and org_treesitter_TSParser.c with
 * -DJNI_OnLoad=<lib>_jni_on_load, because every statically linked JNI library defines that
 * symbol and a binary that links two of them would not. The declaration below is renamed
 * along with the definition, so this call still resolves.
 */

#include <jni.h>

extern jint JNI_OnLoad(JavaVM *vm, void *reserved);

JNIEXPORT jint JNICALL
Java_org_treesitter_utils_NativeUtils_initNative(JNIEnv *env, jclass cls) {
    JavaVM *vm = 0;
    if (JNI_OK != (*env)->GetJavaVM(env, &vm)) {
        return -1;
    }
    return JNI_OnLoad(vm, 0);
}
