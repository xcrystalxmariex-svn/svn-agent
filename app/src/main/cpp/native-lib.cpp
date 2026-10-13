// SVN Agent — Node.js for Mobile JNI bridge.
//
// Runs a full Node.js engine in-process (nodejs-mobile libnode.so) on a
// background thread, started from Java via NodeMobile.nativeStart(). The
// library is bound at runtime with dlopen/dlsym (symbol `node::Start`), so
// the APK can either ship libnode.so under jniLibs/arm64-v8a/ or download it
// at first run and System.load() it — the build never needs libnode present.
//
// process.stdout/stderr are redirected into a pipe whose read end is handed
// to Java (nativeRedirectStdio), so the bundled JS output streams into the
// in-app TerminalView.

#include <jni.h>
#include <dlfcn.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <android/log.h>

#define LOG_TAG "SVNNode"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// node::Start(int argc, char* argv[])  ->  Itanium ABI mangled name.
typedef int (*node_start_t)(int, char*[]);
static const char* kNODE_START_SYMS[] = {
    "_ZN4node5StartEiPPc",
    "_ZN4node5StartEiPPKc",
    "node_Start",
};

static int g_read_fd = -1;
static int g_saved_stdout = -1;
static int g_saved_stderr = -1;

static node_start_t resolveNodeStart(void* handle) {
    for (size_t i = 0; i < sizeof(kNODE_START_SYMS) / sizeof(kNODE_START_SYMS[0]); i++) {
        void* p = dlsym(handle, kNODE_START_SYMS[i]);
        if (p != nullptr) return reinterpret_cast<node_start_t>(p);
    }
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_svndev_svnagent_NodeMobile_nativeRedirectStdio(JNIEnv* env, jclass clazz) {
    int fds[2];
    if (pipe(fds) != 0) {
        LOGE("pipe() failed");
        return -1;
    }
    g_saved_stdout = dup(STDOUT_FILENO);
    g_saved_stderr = dup(STDERR_FILENO);
    dup2(fds[1], STDOUT_FILENO);
    dup2(fds[1], STDERR_FILENO);
    close(fds[1]);
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    g_read_fd = fds[0];
    int flags = fcntl(g_read_fd, F_GETFL, 0);
    fcntl(g_read_fd, F_SETFL, flags | O_NONBLOCK);
    return g_read_fd;
}

extern "C" JNIEXPORT void JNICALL
Java_com_svndev_svnagent_NodeMobile_nativeRestoreStdio(JNIEnv* env, jclass clazz) {
    if (g_saved_stdout >= 0) {
        dup2(g_saved_stdout, STDOUT_FILENO);
        close(g_saved_stdout);
        g_saved_stdout = -1;
    }
    if (g_saved_stderr >= 0) {
        dup2(g_saved_stderr, STDERR_FILENO);
        close(g_saved_stderr);
        g_saved_stderr = -1;
    }
    g_read_fd = -1;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_svndev_svnagent_NodeMobile_nativeStart(JNIEnv* env, jclass clazz,
                                                jstring jLibPath, jobjectArray jArgs) {
    const char* libPath = nullptr;
    if (jLibPath != nullptr) libPath = env->GetStringUTFChars(jLibPath, nullptr);

    void* handle = nullptr;
    if (libPath != nullptr && libPath[0] != '\0') {
        handle = dlopen(libPath, RTLD_NOW | RTLD_GLOBAL);
    }
    if (handle == nullptr) {
        // Fall back to the jniLibs copy (already on the loader search path).
        handle = dlopen("libnode.so", RTLD_NOW | RTLD_GLOBAL);
    }
    if (jLibPath != nullptr && libPath != nullptr) env->ReleaseStringUTFChars(jLibPath, libPath);

    if (handle == nullptr) {
        LOGE("dlopen(libnode) failed: %s", dlerror());
        return -2;
    }

    node_start_t nodeStart = resolveNodeStart(handle);
    if (nodeStart == nullptr) {
        LOGE("dlsym(node::Start) failed");
        return -3;
    }

    jsize argc = env->GetArrayLength(jArgs);
    std::vector<std::string> store(static_cast<size_t>(argc));
    std::vector<char*> argv(static_cast<size_t>(argc) + 1);
    for (jsize i = 0; i < argc; i++) {
        jstring js = static_cast<jstring>(env->GetObjectArrayElement(jArgs, i));
        const char* c = js != nullptr ? env->GetStringUTFChars(js, nullptr) : nullptr;
        store[static_cast<size_t>(i)] = c != nullptr ? c : "";
        if (js != nullptr && c != nullptr) env->ReleaseStringUTFChars(js, c);
        if (js != nullptr) env->DeleteLocalRef(js);
    }
    for (jsize i = 0; i < argc; i++) argv[static_cast<size_t>(i)] = const_cast<char*>(store[static_cast<size_t>(i)].c_str());
    argv[static_cast<size_t>(argc)] = nullptr;

    LOGI("node::Start argc=%d", static_cast<int>(argc));
    int rc = nodeStart(static_cast<int>(argc), argv.data());
    LOGI("node::Start returned %d", rc);
    return rc;
}
