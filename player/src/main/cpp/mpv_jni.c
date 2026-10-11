#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <android/log.h>
#include <mpv/client.h>
#include <libavcodec/jni.h>

#define LOG_TAG "mpv"

static JavaVM *g_vm = NULL;
static jclass g_boolean_class = NULL;
static jmethodID g_boolean_valueOf = NULL;
static jclass g_long_class = NULL;
static jmethodID g_long_valueOf = NULL;
static jclass g_double_class = NULL;
static jmethodID g_double_valueOf = NULL;
static jclass g_callback_class = NULL;
static jmethodID g_cb_on_prop = NULL;
static jmethodID g_cb_on_event = NULL;

typedef struct {
    mpv_handle *mpv;
    pthread_t event_thread;
    int thread_running;
    int stop_requested;
    jobject surface_ref;
    jobject callback;
    pthread_mutex_t lock;
} mpv_holder_t;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_vm = vm;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    if (av_jni_set_java_vm(vm, NULL) < 0) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "av_jni_set_java_vm failed");
    }

    jclass b_cls = (*env)->FindClass(env, "java/lang/Boolean");
    g_boolean_class = (jclass)(*env)->NewGlobalRef(env, b_cls);
    g_boolean_valueOf = (*env)->GetStaticMethodID(env, g_boolean_class, "valueOf", "(Z)Ljava/lang/Boolean;");
    (*env)->DeleteLocalRef(env, b_cls);

    jclass l_cls = (*env)->FindClass(env, "java/lang/Long");
    g_long_class = (jclass)(*env)->NewGlobalRef(env, l_cls);
    g_long_valueOf = (*env)->GetStaticMethodID(env, g_long_class, "valueOf", "(J)Ljava/lang/Long;");
    (*env)->DeleteLocalRef(env, l_cls);

    jclass d_cls = (*env)->FindClass(env, "java/lang/Double");
    g_double_class = (jclass)(*env)->NewGlobalRef(env, d_cls);
    g_double_valueOf = (*env)->GetStaticMethodID(env, g_double_class, "valueOf", "(D)Ljava/lang/Double;");
    (*env)->DeleteLocalRef(env, d_cls);

    jclass cb_cls = (*env)->FindClass(env, "com/vibeplayer/player/MpvNative$EventCallback");
    if (cb_cls) {
        g_callback_class = (jclass)(*env)->NewGlobalRef(env, cb_cls);
        g_cb_on_prop = (*env)->GetMethodID(env, g_callback_class, "onPropertyChange", "(Ljava/lang/String;Ljava/lang/Object;)V");
        g_cb_on_event = (*env)->GetMethodID(env, g_callback_class, "onEvent", "(I)V");
        (*env)->DeleteLocalRef(env, cb_cls);
    } else {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "EventCallback class not found in JNI_OnLoad");
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
    }

    return JNI_VERSION_1_6;
}

static void *event_thread_func(void *arg) {
    mpv_holder_t *holder = (mpv_holder_t *)arg;
    JNIEnv *env = NULL;
    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "AttachCurrentThread failed");
        return NULL;
    }

    while (!holder->stop_requested) {
        mpv_event *event = mpv_wait_event(holder->mpv, -1.0);
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
            continue;
        }

        pthread_mutex_lock(&holder->lock);
        jobject callback = holder->callback;
        if (callback && g_cb_on_prop && g_cb_on_event) {
            if (event->event_id == MPV_EVENT_PROPERTY_CHANGE) {
                mpv_event_property *prop = (mpv_event_property *)event->data;
                if (prop && prop->name) {
                    jobject jval = NULL;
                    if (prop->data != NULL) {
                        switch (prop->format) {
                            case MPV_FORMAT_STRING:
                            case MPV_FORMAT_OSD_STRING: {
                                const char *str = *(const char **)prop->data;
                                if (str) {
                                    jval = (*env)->NewStringUTF(env, str);
                                }
                                break;
                            }
                            case MPV_FORMAT_FLAG: {
                                int flag = *(int *)prop->data;
                                jval = (*env)->CallStaticObjectMethod(env, g_boolean_class, g_boolean_valueOf, (jboolean)(flag != 0));
                                break;
                            }
                            case MPV_FORMAT_INT64: {
                                int64_t val = *(int64_t *)prop->data;
                                jval = (*env)->CallStaticObjectMethod(env, g_long_class, g_long_valueOf, (jlong)val);
                                break;
                            }
                            case MPV_FORMAT_DOUBLE: {
                                double val = *(double *)prop->data;
                                jval = (*env)->CallStaticObjectMethod(env, g_double_class, g_double_valueOf, (jdouble)val);
                                break;
                            }
                            default:
                                break;
                        }
                    }
                    jstring jname = (*env)->NewStringUTF(env, prop->name);
                    (*env)->CallVoidMethod(env, callback, g_cb_on_prop, jname, jval);
                    if ((*env)->ExceptionCheck(env)) {
                        (*env)->ExceptionDescribe(env);
                        (*env)->ExceptionClear(env);
                    }
                    if (jname) (*env)->DeleteLocalRef(env, jname);
                    if (jval) (*env)->DeleteLocalRef(env, jval);
                }
            } else {
                (*env)->CallVoidMethod(env, callback, g_cb_on_event, (jint)event->event_id);
                if ((*env)->ExceptionCheck(env)) {
                    (*env)->ExceptionDescribe(env);
                    (*env)->ExceptionClear(env);
                }
            }
        }
        pthread_mutex_unlock(&holder->lock);
    }

    (*g_vm)->DetachCurrentThread(g_vm);
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
    pthread_mutex_init(&holder->lock, NULL);

    if (pthread_create(&holder->event_thread, NULL, event_thread_func, holder) == 0) {
        holder->thread_running = 1;
    } else {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "failed to create event thread");
    }

    return (jlong)(intptr_t)holder;
}

JNIEXPORT void JNICALL
Java_com_vibeplayer_player_MpvNative_setEventCallback(JNIEnv *env, jobject thiz,
                                                      jlong handle, jobject callback) {
    if (!handle) return;
    mpv_holder_t *holder = (mpv_holder_t *)(intptr_t)handle;
    jobject new_ref = callback ? (*env)->NewGlobalRef(env, callback) : NULL;
    pthread_mutex_lock(&holder->lock);
    jobject old_ref = holder->callback;
    holder->callback = new_ref;
    pthread_mutex_unlock(&holder->lock);
    if (old_ref) {
        (*env)->DeleteGlobalRef(env, old_ref);
    }
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

JNIEXPORT jstring JNICALL
Java_com_vibeplayer_player_MpvNative_getPropertyString(JNIEnv *env, jobject thiz,
                                                        jlong handle, jstring name) {
    if (!handle || !name) return NULL;
    mpv_holder_t *holder = (mpv_holder_t *)(intptr_t)handle;
    const char *c_name = (*env)->GetStringUTFChars(env, name, NULL);
    char *val = mpv_get_property_string(holder->mpv, c_name);
    (*env)->ReleaseStringUTFChars(env, name, c_name);
    if (!val) return NULL;
    jstring res = (*env)->NewStringUTF(env, val);
    mpv_free(val);
    return res;
}

JNIEXPORT jint JNICALL
Java_com_vibeplayer_player_MpvNative_setPropertyString(JNIEnv *env, jobject thiz,
                                                        jlong handle, jstring name, jstring value) {
    if (!handle || !name || !value) return -1;
    mpv_holder_t *holder = (mpv_holder_t *)(intptr_t)handle;
    const char *c_name = (*env)->GetStringUTFChars(env, name, NULL);
    const char *c_value = (*env)->GetStringUTFChars(env, value, NULL);
    int res = mpv_set_property_string(holder->mpv, c_name, c_value);
    (*env)->ReleaseStringUTFChars(env, name, c_name);
    (*env)->ReleaseStringUTFChars(env, value, c_value);
    return res;
}

JNIEXPORT jobject JNICALL
Java_com_vibeplayer_player_MpvNative_getPropertyBoolean(JNIEnv *env, jobject thiz,
                                                         jlong handle, jstring name) {
    if (!handle || !name) return NULL;
    mpv_holder_t *holder = (mpv_holder_t *)(intptr_t)handle;
    const char *c_name = (*env)->GetStringUTFChars(env, name, NULL);
    int flag = 0;
    int res = mpv_get_property(holder->mpv, c_name, MPV_FORMAT_FLAG, &flag);
    (*env)->ReleaseStringUTFChars(env, name, c_name);
    if (res < 0) return NULL;
    return (*env)->CallStaticObjectMethod(env, g_boolean_class, g_boolean_valueOf, (jboolean)(flag != 0));
}

JNIEXPORT jint JNICALL
Java_com_vibeplayer_player_MpvNative_setPropertyBoolean(JNIEnv *env, jobject thiz,
                                                         jlong handle, jstring name, jboolean value) {
    if (!handle || !name) return -1;
    mpv_holder_t *holder = (mpv_holder_t *)(intptr_t)handle;
    const char *c_name = (*env)->GetStringUTFChars(env, name, NULL);
    int flag = value ? 1 : 0;
    int res = mpv_set_property(holder->mpv, c_name, MPV_FORMAT_FLAG, &flag);
    (*env)->ReleaseStringUTFChars(env, name, c_name);
    return res;
}

JNIEXPORT jobject JNICALL
Java_com_vibeplayer_player_MpvNative_getPropertyLong(JNIEnv *env, jobject thiz,
                                                     jlong handle, jstring name) {
    if (!handle || !name) return NULL;
    mpv_holder_t *holder = (mpv_holder_t *)(intptr_t)handle;
    const char *c_name = (*env)->GetStringUTFChars(env, name, NULL);
    int64_t val = 0;
    int res = mpv_get_property(holder->mpv, c_name, MPV_FORMAT_INT64, &val);
    (*env)->ReleaseStringUTFChars(env, name, c_name);
    if (res < 0) return NULL;
    return (*env)->CallStaticObjectMethod(env, g_long_class, g_long_valueOf, (jlong)val);
}

JNIEXPORT jint JNICALL
Java_com_vibeplayer_player_MpvNative_setPropertyLong(JNIEnv *env, jobject thiz,
                                                     jlong handle, jstring name, jlong value) {
    if (!handle || !name) return -1;
    mpv_holder_t *holder = (mpv_holder_t *)(intptr_t)handle;
    const char *c_name = (*env)->GetStringUTFChars(env, name, NULL);
    int64_t val = (int64_t)value;
    int res = mpv_set_property(holder->mpv, c_name, MPV_FORMAT_INT64, &val);
    (*env)->ReleaseStringUTFChars(env, name, c_name);
    return res;
}

JNIEXPORT jobject JNICALL
Java_com_vibeplayer_player_MpvNative_getPropertyDouble(JNIEnv *env, jobject thiz,
                                                       jlong handle, jstring name) {
    if (!handle || !name) return NULL;
    mpv_holder_t *holder = (mpv_holder_t *)(intptr_t)handle;
    const char *c_name = (*env)->GetStringUTFChars(env, name, NULL);
    double val = 0.0;
    int res = mpv_get_property(holder->mpv, c_name, MPV_FORMAT_DOUBLE, &val);
    (*env)->ReleaseStringUTFChars(env, name, c_name);
    if (res < 0) return NULL;
    return (*env)->CallStaticObjectMethod(env, g_double_class, g_double_valueOf, (jdouble)val);
}

JNIEXPORT jint JNICALL
Java_com_vibeplayer_player_MpvNative_setPropertyDouble(JNIEnv *env, jobject thiz,
                                                       jlong handle, jstring name, jdouble value) {
    if (!handle || !name) return -1;
    mpv_holder_t *holder = (mpv_holder_t *)(intptr_t)handle;
    const char *c_name = (*env)->GetStringUTFChars(env, name, NULL);
    double val = (double)value;
    int res = mpv_set_property(holder->mpv, c_name, MPV_FORMAT_DOUBLE, &val);
    (*env)->ReleaseStringUTFChars(env, name, c_name);
    return res;
}

JNIEXPORT jint JNICALL
Java_com_vibeplayer_player_MpvNative_observeProperty(JNIEnv *env, jobject thiz,
                                                     jlong handle, jstring name, jint format) {
    if (!handle || !name) return -1;
    mpv_holder_t *holder = (mpv_holder_t *)(intptr_t)handle;
    const char *c_name = (*env)->GetStringUTFChars(env, name, NULL);
    int res = mpv_observe_property(holder->mpv, 0, c_name, (mpv_format)format);
    (*env)->ReleaseStringUTFChars(env, name, c_name);
    return res;
}

JNIEXPORT jint JNICALL
Java_com_vibeplayer_player_MpvNative_unobserveProperty(JNIEnv *env, jobject thiz,
                                                       jlong handle, jlong reply_userdata) {
    if (!handle) return -1;
    mpv_holder_t *holder = (mpv_holder_t *)(intptr_t)handle;
    return mpv_unobserve_property(holder->mpv, (uint64_t)reply_userdata);
}

JNIEXPORT void JNICALL
Java_com_vibeplayer_player_MpvNative_attachSurface(JNIEnv *env, jobject thiz,
                                                   jlong handle, jobject surface) {
    if (!handle || !surface) return;
    mpv_holder_t *holder = (mpv_holder_t *)(intptr_t)handle;
    if (holder->surface_ref) {
        int64_t wid = 0;
        mpv_set_option(holder->mpv, "wid", MPV_FORMAT_INT64, &wid);
        (*env)->DeleteGlobalRef(env, holder->surface_ref);
        holder->surface_ref = NULL;
    }
    jobject g_ref = (*env)->NewGlobalRef(env, surface);
    holder->surface_ref = g_ref;
    int64_t wid = (intptr_t)g_ref;
    int res = mpv_set_option(holder->mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (res < 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "mpv_set_option(wid) failed: %s", mpv_error_string(res));
    }
}

JNIEXPORT void JNICALL
Java_com_vibeplayer_player_MpvNative_detachSurface(JNIEnv *env, jobject thiz,
                                                   jlong handle) {
    if (!handle) return;
    mpv_holder_t *holder = (mpv_holder_t *)(intptr_t)handle;
    int64_t wid = 0;
    mpv_set_option(holder->mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (holder->surface_ref) {
        (*env)->DeleteGlobalRef(env, holder->surface_ref);
        holder->surface_ref = NULL;
    }
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
    if (holder->surface_ref) {
        int64_t wid = 0;
        mpv_set_option(holder->mpv, "wid", MPV_FORMAT_INT64, &wid);
        (*env)->DeleteGlobalRef(env, holder->surface_ref);
        holder->surface_ref = NULL;
    }
    if (holder->callback) {
        (*env)->DeleteGlobalRef(env, holder->callback);
        holder->callback = NULL;
    }
    if (holder->mpv) {
        mpv_terminate_destroy(holder->mpv);
        holder->mpv = NULL;
    }
    pthread_mutex_destroy(&holder->lock);
    free(holder);
}
