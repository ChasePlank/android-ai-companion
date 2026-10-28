package com.chase.companion

/** Delegate primitive entity-array mapping to Kotlin's Iterable overload. */
internal inline fun <R : Any> IntArray.mapNotNull(transform: (Int) -> R?): List<R> =
    asIterable().mapNotNull(transform)
