package ru.light.gtnh.updater.presentation.tui


import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal class BackgroundOperation<T>(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    operation: suspend () -> T,
) {
    private val scope = CoroutineScope(dispatcher)
    private val started = atomic(false)
    private val cancellationRequested = atomic(false)
    private val result = atomic<Result<T>?>(null)

    init {
        scope.launch {
            started.value = true
            result.value = runCatching {
                if (cancellationRequested.value) throw CancellationException()
                operation()
            }
        }
    }

    fun outcome(): Result<T>? = result.value

    fun cancel() {
        cancellationRequested.value = true
        if (started.value) scope.cancel()
    }
}
