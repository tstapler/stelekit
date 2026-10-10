package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.runtime.Composable

/**
 * Type-safe drop-in for `LazyListScope.items` that enforces a `String` key.
 *
 * Compose's built-in `items(key: ((T) -> Any)?)` accepts any type, which means passing a
 * domain value class (`PageUuid`, `BlockUuid`) compiles fine but crashes on Android at
 * runtime: `SaveableStateHolder` rejects non-Bundle-safe key types with
 * `IllegalArgumentException`. Switching to this wrapper makes the same mistake a compile
 * error instead.
 *
 * Usage:
 * ```kotlin
 * // Before (compiles, crashes on Android):
 * items(pages, key = { it.uuid })
 *
 * // After (compile error if key is not String):
 * typedItems(pages, key = { it.uuid.asLazyKey() })
 * ```
 */
inline fun <T> LazyListScope.typedItems(
    items: List<T>,
    crossinline key: (item: T) -> String,
    crossinline contentType: (item: T) -> Any? = { null },
    crossinline itemContent: @Composable LazyItemScope.(item: T) -> Unit,
) {
    items(
        count = items.size,
        key = { index -> key(items[index]) },
        contentType = { index -> contentType(items[index]) },
    ) { index ->
        itemContent(items[index])
    }
}

/**
 * Type-safe drop-in for `LazyListScope.itemsIndexed` that enforces a `String` key.
 * See [typedItems] for motivation.
 */
inline fun <T> LazyListScope.typedItemsIndexed(
    items: List<T>,
    crossinline key: (index: Int, item: T) -> String,
    crossinline contentType: (index: Int, item: T) -> Any? = { _, _ -> null },
    crossinline itemContent: @Composable LazyItemScope.(index: Int, item: T) -> Unit,
) {
    items(
        count = items.size,
        key = { index -> key(index, items[index]) },
        contentType = { index -> contentType(index, items[index]) },
    ) { index ->
        itemContent(index, items[index])
    }
}

/**
 * Type-safe drop-in for `LazyGridScope.items` that enforces a `String` key.
 * See [typedItems] for motivation.
 *
 * @param span optional span override; receives the item index so callers can vary grid
 *   column span per item. Example: `span = { index -> if (index == 0) GridItemSpan(maxLineSpan) else GridItemSpan(1) }`.
 *   If null, all items use the grid's default span.
 */
inline fun <T> LazyGridScope.typedGridItems(
    items: List<T>,
    crossinline key: (item: T) -> String,
    noinline span: (LazyGridItemSpanScope.(index: Int) -> GridItemSpan)? = null,
    crossinline contentType: (item: T) -> Any? = { null },
    crossinline itemContent: @Composable LazyGridItemScope.(item: T) -> Unit,
) {
    items(
        count = items.size,
        key = { index -> key(items[index]) },
        span = span,
        contentType = { index -> contentType(items[index]) },
    ) { index ->
        itemContent(items[index])
    }
}

/**
 * Type-safe drop-in for `LazyGridScope.itemsIndexed` that enforces a `String` key.
 * See [typedItems] for motivation.
 */
inline fun <T> LazyGridScope.typedGridItemsIndexed(
    items: List<T>,
    crossinline key: (index: Int, item: T) -> String,
    noinline span: (LazyGridItemSpanScope.(index: Int) -> GridItemSpan)? = null,
    crossinline contentType: (index: Int, item: T) -> Any? = { _, _ -> null },
    crossinline itemContent: @Composable LazyGridItemScope.(index: Int, item: T) -> Unit,
) {
    items(
        count = items.size,
        key = { index -> key(index, items[index]) },
        span = span,
        contentType = { index -> contentType(index, items[index]) },
    ) { index ->
        itemContent(index, items[index])
    }
}
