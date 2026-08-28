package com.azhidkov.mystuff

import android.os.Handler
import android.os.Looper
import com.google.firebase.functions.FirebaseFunctions
import java.util.concurrent.atomic.AtomicBoolean

fun interface SearchSubscription {
    fun cancel()
}

interface SearchGateway {
    fun search(
        query: String,
        onResult: (Result<List<String>>) -> Unit,
    ): SearchSubscription
}

interface SearchDebouncer : AutoCloseable {
    fun schedule(delayMillis: Long, action: () -> Unit): SearchSubscription
}

internal object NoSearchGateway : SearchGateway {
    override fun search(
        query: String,
        onResult: (Result<List<String>>) -> Unit,
    ): SearchSubscription = SearchSubscription {}
}

internal object NoSearchDebouncer : SearchDebouncer {
    override fun schedule(delayMillis: Long, action: () -> Unit): SearchSubscription =
        SearchSubscription {}

    override fun close() = Unit
}

class FirebaseSearchGateway(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(SEARCH_FUNCTION_REGION),
) : SearchGateway {
    override fun search(
        query: String,
        onResult: (Result<List<String>>) -> Unit,
    ): SearchSubscription {
        val canceled = AtomicBoolean(false)
        functions
            .getHttpsCallable(SEARCH_FUNCTION_NAME)
            .call(mapOf("query" to query))
            .addOnCompleteListener { task ->
                if (canceled.get()) return@addOnCompleteListener
                val result = if (task.isSuccessful) {
                    runCatching { task.result?.data.searchItemIds() }
                } else {
                    Result.failure(task.exception ?: IllegalStateException("Search failed."))
                }
                onResult(result)
            }
        return SearchSubscription { canceled.set(true) }
    }
}

class MainThreadSearchDebouncer(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : SearchDebouncer {
    private val scheduled = mutableSetOf<Runnable>()

    override fun schedule(delayMillis: Long, action: () -> Unit): SearchSubscription {
        lateinit var runnable: Runnable
        runnable = Runnable {
            scheduled -= runnable
            action()
        }
        scheduled += runnable
        handler.postDelayed(runnable, delayMillis)
        return SearchSubscription {
            scheduled -= runnable
            handler.removeCallbacks(runnable)
        }
    }

    override fun close() {
        scheduled.toList().forEach(handler::removeCallbacks)
        scheduled.clear()
    }
}

private fun Any?.searchItemIds(): List<String> {
    val response = this as? Map<*, *> ?: error("Search returned an invalid response.")
    val itemIds = response["itemIds"] as? List<*>
        ?: error("Search returned no Item IDs.")
    return itemIds.map { itemId ->
        itemId as? String ?: error("Search returned an invalid Item ID.")
    }
}

internal const val CONCEPTUAL_SEARCH_DEBOUNCE_MILLIS = 500L
internal const val SEARCH_FUNCTION_REGION = "australia-southeast1"
private const val SEARCH_FUNCTION_NAME = "searchInventory"

internal fun String.isConceptualSearchEligible(): Boolean =
    trimUnicodeWhitespace().codePoints().filter(Character::isLetterOrDigit).limit(3).count() >= 3
