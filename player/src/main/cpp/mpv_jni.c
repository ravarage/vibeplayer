#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <android/log.h>
#include <mpv/client.h>
#include <libavcodec/jni.h>

#define LOG_TAG "mpv"

static JavaVM *g_vm = NULL;

typedef struct {
    mpv_handle *mpv;
    pthread_t event_thread;
    int thread_running;
    int stop_requested;
} mpv_holder_t;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_vm = vm;
    if (av_jni_set_java_vm(vm, NULL) < 0) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "av_jni_set_java_vm failed");
    }
    return JNI_VERSION_1_6;
}

static void *event_thread_func(void *arg) {
    mpv_holder_t *holder = (mpv_holder_t *)arg;
    while (!holder->stop_requested) {
        mpv_event *event = mpv_wait_event(holder->mpv, 0.5);
        if (!event || event->event_id == MPV_EVENT_NONE) {
            continue;
        }
        if (event->event_id == MPV_EVENT_SHUTDOWN || holder->stop_requested) {
            break;
        }
        if (event->event_id == MPV_EVENT_LOG_MESSAGE) {
            mpv_event_log_message *msg = (mpv_event_log_message *)event->data;
            int prio = ANDROID_LOG_INFO;
            if (msg->log_level <= MPV_LOG_LEVEL_ERROR) {
                prio = ANDROID_LOG_ERROR;
            } else if (msg->log_level <= MPV_LOG_LEVEL_WARN) {
                prio = ANDROID_LOG_WARN;
            } else if (msg->log_level <= MPV_LOG_LEVEL_INFO) {
                prio = ANDROID_LOG_INFO;
            } else {
                prio = ANDROID_LOG_DEBUG;
            }
            char text_buf[512];
            size_t len = strlen(msg->text);
            if (len > 0 && msg->text[len - 1] == '\n') {
                size_t copy_len = len - 1 < sizeof(text_buf) - 1 ? len - 1 : sizeof(text_buf) - 1;
                memcpy(text_buf, msg->text, copy_len);
                text_buf[copy_len] = '\0';
                __android_log_print(prio, LOG_TAG, "[%s] %s", msg->prefix, text_buf);
            } else {
                __android_log_print(prio, LOG_TAG, "[%s] %s", msg->prefix, msg->text);
            }
        }
    }
    return NULL;
}

JNIEXPORT jlong JNICALL
Java_com_vibeplayer_player_MpvNative_create(JNIEnv *env, jobject thiz) {
    mpv_handle *mpv = mpv_create();
    if (!mpv) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "mpv_create() failed");
        return 0;
    }

    mpv_request_log_messages(mpv, "terminal-default");
    mpv_set_option_string(mpv, "msg-level", "all=v");

    mpv_holder_t *holder = (mpv_holder_t *)calloc(1, sizeof(mpv_holder_t));
    holder->mpv = mpv;

    if (pthread_create(&holder->event_thread, NULL, event_thread_func, holder) == 0) {
        holder->thread_running = 1;
    } else {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "failed to create event thread");
    }

    return (jlong)(intptr_t)holder;
}

JNIEXPORT jint JNICALL
Java_com_vibeplayer_player_MpvNative_setOptionString(JNIEnv *env, jobject thiz,
                                                     jlong handle, jstring name, jstring value) {
    if (!handle) return -1;
    mpv_holder_t *holder = (mpv_holder_t *)(intptr_t)handle;
    const char *c_name = (*env)->GetStringUTFChars(env, name, NULL);
    const char *c_value = (*env)->GetStringUTFChars(env, value, NULL);
    int res = mpv_set_option_string(holder->mpv, c_name, c_value);
    (*env)->ReleaseStringUTFChars(env, name, c_name);
    (*env)->ReleaseStringUTFChars(env, value, c_value);
    return res;
}

JNIEXPORT jint JNICALL
Java_com_vibeplayer_player_MpvNative_initialize(JNIEnv *env, jobject thiz, jlong handle) {
    if (!handle) return -1;
    mpv_holder_t *holder = (mpv_holder_t *)(intptr_t)handle;
    int res = mpv_initialize(holder->mpv);
    if (res < 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "mpv_initialize failed: %s", mpv_error_string(res));
    } else {
        char *ver = mpv_get_property_string(holder->mpv, "mpv-version");
        if (ver) {
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "mpv version: %s", ver);
            mpv_free(ver);
        }
    }
    return res;
}

JNIEXPORT jint JNICALL
Java_com_vibeplayer_player_MpvNative_command(JNIEnv *env, jobject thiz,
                                             jlong handle, jobjectArray args) {
    if (!handle || !args) return -1;
    mpv_holder_t *holder = (mpv_holder_t *)(intptr_t)handle;
    jsize len = (*env)->GetArrayLength(env, args);
    const char **cmd_args = (const char **)calloc(len + 1, sizeof(const char *));
    for (jsize i = 0; i < len; i++) {
        jstring str = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        if (str) {
            cmd_args[i] = (*env)->GetStringUTFChars(env, str, NULL);
        }
    }
    cmd_args[len] = NULL;
    int res = mpv_command(holder->mpv, cmd_args);
    for (jsize i = 0; i < len; i++) {
        if (cmd_args[i]) {
            jstring str = (jstring)(*env)->GetObjectArrayElement(env, args, i);
            if (str) {
                (*env)->ReleaseStringUTFChars(env, str, cmd_args[i]);
                (*env)->DeleteLocalRef(env, str);
            }
        }
    }
    free(cmd_args);
    return res;
}

JNIEXPORT void JNICALL
Java_com_vibeplayer_player_MpvNative_destroy(JNIEnv *env, jobject thiz, jlong handle) {
    if (!handle) return;
    mpv_holder_t *holder = (mpv_holder_t *)(intptr_t)handle;
    holder->stop_requested = 1;
    if (holder->mpv) {
        mpv_wakeup(holder->mpv);
    }
    if (holder->thread_running) {
        pthread_join(holder->event_thread, NULL);
        holder->thread_running = 0;
    }
    if (holder->mpv) {
        mpv_terminate_destroy(holder->mpv);
        holder->mpv = NULL;
    }
    free(holder);
}
