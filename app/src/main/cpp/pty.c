/*
 * droid_codology pseudo-terminal: starts a process on a new pty and gives Java the master fd.
 * Reads and writes go through here too, so no hidden FileDescriptor fields are needed.
 */
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define CLASS(name) Java_com_disgusty_droid_1codology_term_Pty_##name

static void throw_io(JNIEnv *env, const char *msg) {
    jclass c = (*env)->FindClass(env, "java/io/IOException");
    if (c) (*env)->ThrowNew(env, c, msg);
}

static char **to_cstrings(JNIEnv *env, jobjectArray arr) {
    if (!arr) return NULL;
    jsize n = (*env)->GetArrayLength(env, arr);
    char **out = (char **) calloc((size_t) n + 1, sizeof(char *));
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        const char *u = (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = strdup(u);
        (*env)->ReleaseStringUTFChars(env, s, u);
        (*env)->DeleteLocalRef(env, s);
    }
    return out;
}

/* Returns the master fd; pidOut[0] receives the child pid. */
JNIEXPORT jint JNICALL CLASS(start)(JNIEnv *env, jclass cls, jstring jcmd, jobjectArray jargs, jobjectArray jenv,
                                     jstring jcwd, jint rows, jint cols, jintArray pidOut) {
    int master = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (master < 0) {
        throw_io(env, "cannot open /dev/ptmx");
        return -1;
    }
    char slave_name[64];
    if (grantpt(master) || unlockpt(master) || ptsname_r(master, slave_name, sizeof(slave_name))) {
        close(master);
        throw_io(env, "cannot set up the pseudo-terminal");
        return -1;
    }
    /* UTF-8 aware line discipline, no flow control (Ctrl+S would freeze the terminal). */
    struct termios tios;
    tcgetattr(master, &tios);
    tios.c_iflag |= IUTF8;
    tios.c_iflag &= ~(IXON | IXOFF);
    tcsetattr(master, TCSANOW, &tios);
    struct winsize ws = {(unsigned short) rows, (unsigned short) cols, 0, 0};
    ioctl(master, TIOCSWINSZ, &ws);

    const char *cmd = (*env)->GetStringUTFChars(env, jcmd, NULL);
    const char *cwd = jcwd ? (*env)->GetStringUTFChars(env, jcwd, NULL) : NULL;
    char **argv = to_cstrings(env, jargs);
    char **envp = to_cstrings(env, jenv);

    pid_t pid = fork();
    if (pid < 0) {
        close(master);
        throw_io(env, "fork failed");
        return -1;
    }
    if (pid == 0) {
        /* Child: new session with the pty as controlling terminal. */
        sigset_t all;
        sigfillset(&all);
        sigprocmask(SIG_UNBLOCK, &all, NULL);
        /* Zygote ignores SIGHUP (and more), which exec keeps: reset every signal, or closing the
         * terminal (hang-up) wouldn't end the shell and its jobs. */
        for (int sig = 1; sig < NSIG; sig++) signal(sig, SIG_DFL);
        close(master);
        setsid();
        int slave = open(slave_name, O_RDWR);
        if (slave < 0) _exit(1);
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        if (slave > 2) close(slave);
        /* Close everything else inherited from the app. */
        for (int fd = 3; fd < 1024; fd++) close(fd);
        if (cwd && chdir(cwd) != 0) chdir("/");
        if (envp) {
            clearenv();
            for (char **e = envp; *e; e++) putenv(*e);
        }
        execvp(cmd, argv);
        const char *msg = "droid_codology: cannot start the shell\r\n";
        write(1, msg, strlen(msg));
        _exit(127);
    }
    (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
    if (cwd) (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
    jint p = (jint) pid;
    (*env)->SetIntArrayRegion(env, pidOut, 0, 1, &p);
    /* argv/envp copies are leaked in the parent on purpose: tiny and once per terminal. */
    return master;
}

JNIEXPORT jint JNICALL CLASS(read)(JNIEnv *env, jclass cls, jint fd, jbyteArray buf, jint off, jint len) {
    jbyte tmp[8192];
    if (len > (jint) sizeof(tmp)) len = sizeof(tmp);
    ssize_t n;
    do {
        n = read(fd, tmp, (size_t) len);
    } while (n < 0 && errno == EINTR);
    if (n <= 0) return -1; /* EIO when the child exits */
    (*env)->SetByteArrayRegion(env, buf, off, (jsize) n, tmp);
    return (jint) n;
}

JNIEXPORT void JNICALL CLASS(write)(JNIEnv *env, jclass cls, jint fd, jbyteArray buf, jint off, jint len) {
    jbyte *b = (*env)->GetByteArrayElements(env, buf, NULL);
    jint done = 0;
    while (done < len) {
        ssize_t n = write(fd, b + off + done, (size_t) (len - done));
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        done += (jint) n;
    }
    (*env)->ReleaseByteArrayElements(env, buf, b, JNI_ABORT);
}

JNIEXPORT void JNICALL CLASS(resize)(JNIEnv *env, jclass cls, jint fd, jint rows, jint cols) {
    struct winsize ws = {(unsigned short) rows, (unsigned short) cols, 0, 0};
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL CLASS(waitFor)(JNIEnv *env, jclass cls, jint pid) {
    int status;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 0;
}

JNIEXPORT void JNICALL CLASS(kill)(JNIEnv *env, jclass cls, jint pid, jint sig) {
    kill(-pid, sig); /* the whole process group of the session */
    kill(pid, sig);
}

JNIEXPORT void JNICALL CLASS(close)(JNIEnv *env, jclass cls, jint fd) {
    close(fd);
}

/* For unpacking Linux file systems (Alpine) on any Android version (android.system.Os is 5.0+). */
JNIEXPORT jint JNICALL CLASS(symlink)(JNIEnv *env, jclass cls, jstring jtarget, jstring jpath) {
    const char *target = (*env)->GetStringUTFChars(env, jtarget, NULL);
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    unlink(path);
    int r = symlink(target, path) == 0 ? 0 : errno;
    (*env)->ReleaseStringUTFChars(env, jtarget, target);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return r;
}

JNIEXPORT jint JNICALL CLASS(chmod)(JNIEnv *env, jclass cls, jstring jpath, jint mode) {
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    int r = chmod(path, (mode_t) mode) == 0 ? 0 : errno;
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return r;
}

/* A named pipe (the Node.js extension host's request/reply channels); 0 or errno. */
JNIEXPORT jint JNICALL CLASS(mkfifo)(JNIEnv *env, jclass cls, jstring jpath, jint mode) {
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    unlink(path);
    int r = mkfifo(path, (mode_t) mode) == 0 ? 0 : errno;
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return r;
}