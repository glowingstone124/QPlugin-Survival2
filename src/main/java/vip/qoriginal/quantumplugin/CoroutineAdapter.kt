package vip.qoriginal.quantumplugin

import kotlinx.coroutines.*
import kotlinx.coroutines.future.asCompletableFuture
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

class CoroutineJava {

    fun push(func: Runnable, dispatcher: CoroutineDispatcher) {
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        scope.launch {
            func.run()
        }
    }

    fun <T> run(func: Callable<T>, dispatcher: CoroutineDispatcher): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        scope.launch {
            try {
                val result = func.call()
                future.complete(result)
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }
        return future
    }
}