#include <jni.h>
#include <time.h>

#include <mpv/client.h>

#include "jni_utils.h"
#include "log.h"
#include "globals.h"
#include "mpv_context.h"

extern "C" {
    jni_func(void, attachSurface, jobject surface_);
    jni_func(jboolean, attachSurfaceWithResult, jobject surface_);
    jni_func(void, detachSurface);
    jni_func(jboolean, detachSurfaceWithResult);
};

static bool wait_for_video_output_detach(MpvContext *ctx) {
    int result = mpv_set_property_string(ctx->mpv, "vo", "null");
    if (result < 0) {
        ALOGE("mpv_set_property(vo=null) returned error %s", mpv_error_string(result));
        return false;
    }

    struct timespec start;
    clock_gettime(CLOCK_MONOTONIC, &start);
    while (true) {
        int configured = 0;
        result = mpv_get_property(ctx->mpv, "vo-configured", MPV_FORMAT_FLAG, &configured);
        if (result == MPV_ERROR_PROPERTY_UNAVAILABLE || (result >= 0 && !configured))
            return true;
        if (result < 0) {
            ALOGE("mpv_get_property(vo-configured) returned error %s", mpv_error_string(result));
            return false;
        }

        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        int64_t elapsed_ms = (now.tv_sec - start.tv_sec) * 1000LL +
            (now.tv_nsec - start.tv_nsec) / 1000000LL;
        if (elapsed_ms >= 1000)
            return false;
        struct timespec pause = {0, 1000000};
        nanosleep(&pause, NULL);
    }
}

static bool detach_surface(JNIEnv *env, MpvContext *ctx) {
    if (!wait_for_video_output_detach(ctx))
        return false;

    int64_t wid = 0;
    int result = mpv_set_option(ctx->mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (result < 0) {
        ALOGE("mpv_set_option(wid=0) returned error %s", mpv_error_string(result));
        return false;
    }

    if (ctx->surface) {
        env->DeleteGlobalRef(ctx->surface);
        ctx->surface = NULL;
    }
    return true;
}

static bool attach_surface(JNIEnv *env, MpvContext *ctx, jobject surface_) {
    if (ctx->surface)
        return false;
    jobject surface = env->NewGlobalRef(surface_);
    if (!surface)
        return false;
    int64_t wid = reinterpret_cast<intptr_t>(surface);
    int result = mpv_set_option(ctx->mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (result < 0) {
        env->DeleteGlobalRef(surface);
        ALOGE("mpv_set_option(wid) returned error %s", mpv_error_string(result));
        return false;
    }
    ctx->surface = surface;
    return true;
}

jni_func(void, attachSurface, jobject surface_) {
    MpvContext *ctx = get_context(env, obj);
    CHECK_CTX(ctx);
    attach_surface(env, ctx, surface_);
}

jni_func(jboolean, attachSurfaceWithResult, jobject surface_) {
    MpvContext *ctx = get_context(env, obj);
    if (!ctx || !ctx->mpv)
        return JNI_FALSE;
    return attach_surface(env, ctx, surface_) ? JNI_TRUE : JNI_FALSE;
}

jni_func(void, detachSurface) {
    MpvContext *ctx = get_context(env, obj);
    CHECK_CTX(ctx);
    detach_surface(env, ctx);
}

jni_func(jboolean, detachSurfaceWithResult) {
    MpvContext *ctx = get_context(env, obj);
    if (!ctx || !ctx->mpv)
        return JNI_FALSE;
    return detach_surface(env, ctx) ? JNI_TRUE : JNI_FALSE;
}
