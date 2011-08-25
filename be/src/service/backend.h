// Copyright (c) 2011 Cloudera, Inc. All rights reserved.

#ifndef IMPALA_SERVICE_BACKEND_H
#define IMPALA_SERVICE_BACKEND_H

#include <jni.h>

namespace impala {

extern "C"
JNIEXPORT void Java_com_cloudera_impala_service_NativeBackend_Init(
    JNIEnv* env, jclass caller_class);

// JNI-callable wrapper to the plan executor
// protected native static void ExecPlan(byte[] thriftExecutePlanRequest,
//      boolean abortOnError, int maxErrors, List<String> errorLog,
//      Map<String, Integer> fileErrors, boolean asAscii,
//      BlockingQueue<TResultRow> resultQueue) throws ImpalaException;
extern "C"
JNIEXPORT void JNICALL Java_com_cloudera_impala_service_NativeBackend_ExecPlan(
    JNIEnv* env, jclass caller_class, jbyteArray thrift_execute_plan_request,
    jboolean abort_on_error, jint max_errors, jobject error_log, jobject file_errors,
    jboolean as_ascii, jobject result_queue);

extern "C"
JNIEXPORT jboolean JNICALL Java_com_cloudera_impala_service_NativeBackend_EvalPredicate(
    JNIEnv* env, jclass caller_class, jbyteArray thrift_predicate);

}

#endif
