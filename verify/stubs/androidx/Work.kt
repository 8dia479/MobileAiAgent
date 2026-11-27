@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.work

import android.content.Context
import kotlinx.coroutines.flow.Flow

class WorkerParameters

abstract class ListenableWorker(val appContext: Context, val workerParams: WorkerParameters) {
    val applicationContext: Context get() = appContext
    abstract suspend fun doWork(): Result
    open fun isStopped(): Boolean = false
    class Result private constructor(val kind: Int) {
        companion object {
            fun success(): Result = Result(0)
            fun success(outputData: Data): Result = Result(0)
            fun failure(): Result = Result(1)
            fun failure(outputData: Data): Result = Result(1)
            fun retry(): Result = Result(2)
        }
    }
}

abstract class CoroutineWorker(appContext: Context, params: WorkerParameters) :
    ListenableWorker(appContext, params) {
    abstract override suspend fun doWork(): Result
}

open class Data
fun workDataOf(vararg pairs: Pair<String, Any?>): Data = Data()

open class WorkRequest
inline fun <reified W : ListenableWorker> OneTimeWorkRequestBuilder(): OneTimeWorkRequest.Builder =
    OneTimeWorkRequest.Builder()

open class OneTimeWorkRequest : WorkRequest() {
    class Builder {
        fun setInputData(data: Data): Builder = this
        fun addTag(tag: String): Builder = this
        fun setBackoffCriteria(policy: Any?, duration: Long, unit: java.util.concurrent.TimeUnit): Builder = this
        fun setConstraints(constraints: Constraints): Builder = this
        fun setInitialDelay(duration: Long, unit: java.util.concurrent.TimeUnit): Builder = this
        fun build(): OneTimeWorkRequest = OneTimeWorkRequest()
    }
}

open class PeriodicWorkRequest : WorkRequest() {
    class Builder {
        fun setInputData(data: Data): Builder = this
        fun addTag(tag: String): Builder = this
        fun build(): PeriodicWorkRequest = PeriodicWorkRequest()
    }
}

class Constraints {
    data class Builder(
        val requiredNetworkType: Int = 0,
        val requiresCharging: Boolean = false,
        val requiresBatteryNotLow: Boolean = false,
    )
}

object NetworkType {
    const val NOT_REQUIRED = 0
    const val CONNECTED = 1
    const val UNMETERED = 2
}

enum class ExistingWorkPolicy { REPLACE, KEEP, APPEND, APPEND_OR_REPLACE }
enum class ExistingPeriodicWorkPolicy { REPLACE, KEEP, UPDATE }

class WorkInfo {
    val state: State get() = State.ENQUEUED
    val tags: Set<String> get() = emptySet()
    val id: java.util.UUID get() = java.util.UUID.randomUUID()
}

enum class State { ENQUEUED, RUNNING, SUCCEEDED, FAILED, BLOCKED, CANCELLED }

abstract class Operation

abstract class WorkManager {
    abstract fun enqueue(request: WorkRequest): Operation
    abstract fun enqueueUniqueWork(name: String, policy: ExistingWorkPolicy, request: WorkRequest): Operation
    abstract fun enqueueUniquePeriodicWork(name: String, policy: ExistingPeriodicWorkPolicy, request: PeriodicWorkRequest): Operation
    abstract fun cancelWorkById(id: java.util.UUID): Operation
    abstract fun cancelUniqueWork(name: String): Operation
    abstract fun getWorkInfosByTag(tag: String): Any
    abstract fun getWorkInfosForUniqueWork(name: String): Any

    companion object {
        fun getInstance(context: Context): WorkManager = throw UnsupportedOperationException("work stub")
    }
}

inline fun <reified W : ListenableWorker> PeriodicWorkRequestBuilder(
    repeatInterval: Long,
    repeatIntervalTimeUnit: java.util.concurrent.TimeUnit,
): PeriodicWorkRequest.Builder = PeriodicWorkRequest.Builder()
