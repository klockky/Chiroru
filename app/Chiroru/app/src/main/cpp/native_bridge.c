#include <jni.h>
#include "backend_registry.h"
#include "device_guard.h"
#include <sys/system_properties.h>

void chiroru_grant_boot_adb_permission(struct rpt *report);

JNIEXPORT jstring JNICALL
Java_dev_chiron_chiroru_NativeBridge_grantBootAdbPermission(JNIEnv *env, jclass cls) {
  (void)cls;
  char buf[4096] = {0};
  struct rpt report = {buf, sizeof buf, 0};
  chiroru_grant_boot_adb_permission(&report);
  return (*env)->NewStringUTF(env, buf);
}

static int allowed_device(void) {
  char device[PROP_VALUE_MAX] = {0}, model[PROP_VALUE_MAX] = {0};
  __system_property_get("ro.product.device", device);
  __system_property_get("ro.product.model", model);
  return chiroru_is_duo_max(device, model);
}

static const struct chiroru_backend *find_backend(JNIEnv *env, jstring id) {
  if (!id || !allowed_device()) return NULL;
  const char *text = (*env)->GetStringUTFChars(env, id, NULL);
  if (!text) return NULL;
  const struct chiroru_backend *backend = chiroru_find_backend(text);
  (*env)->ReleaseStringUTFChars(env, id, text);
  return backend;
}

JNIEXPORT jboolean JNICALL
Java_dev_chiron_chiroru_NativeBridge_supports(JNIEnv *env, jclass cls, jstring id) {
  (void)cls;
  return find_backend(env, id) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_dev_chiron_chiroru_NativeBridge_probe(JNIEnv *env, jclass cls, jstring id) {
  (void)cls;
  const struct chiroru_backend *backend = find_backend(env, id);
  if ((*env)->ExceptionCheck(env)) return NULL;
  return (*env)->NewStringUTF(env, backend ? backend->probe() :
      "preflight: blocked (unknown native backend)\n");
}

JNIEXPORT jstring JNICALL
Java_dev_chiron_chiroru_NativeBridge_exploit(JNIEnv *env, jclass cls, jstring id,
                                          jstring koPath, jstring ksudPath,
                                          jboolean runHooks) {
  (void)cls;
  const struct chiroru_backend *backend = find_backend(env, id);
  if ((*env)->ExceptionCheck(env)) return NULL;
  if (!backend) return (*env)->NewStringUTF(env, "preflight: blocked (unknown native backend)\n");

  char buf[16384] = {0};
  struct rpt report = {buf, sizeof buf, 0};
  const char *ko = koPath ? (*env)->GetStringUTFChars(env, koPath, NULL) : NULL;
  if ((*env)->ExceptionCheck(env)) return NULL;
  const char *ksud = ksudPath ? (*env)->GetStringUTFChars(env, ksudPath, NULL) : NULL;
  if (!(*env)->ExceptionCheck(env)) backend->root(ko, ksud, runHooks == JNI_TRUE, &report);
  if (ko) (*env)->ReleaseStringUTFChars(env, koPath, ko);
  if (ksud) (*env)->ReleaseStringUTFChars(env, ksudPath, ksud);
  if ((*env)->ExceptionCheck(env)) return NULL;
  return (*env)->NewStringUTF(env, buf);
}
