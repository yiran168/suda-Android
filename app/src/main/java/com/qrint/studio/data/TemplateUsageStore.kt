package com.qrint.studio.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small, synchronous preference state: template cards update immediately and survive restarts. */
class TemplateUsageStore(context: Context) {
    companion object {
        private const val PREFS = "template_usage"
        private const val FAVORITES = "favorites"
        private const val RECENT = "recent"
        private const val MAX_RECENT = 40

        internal fun normalizeRecentIds(ids: List<String>, max: Int = MAX_RECENT): List<String> =
            ids.asSequence().map(String::trim).filter(String::isNotBlank).distinct().take(max).toList()
    }

    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _favorites = MutableStateFlow(preferences.getStringSet(FAVORITES, emptySet()).orEmpty().toSet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()
    private val _recent = MutableStateFlow(
        normalizeRecentIds(preferences.getString(RECENT, "").orEmpty().split('\n')),
    )
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    fun toggleFavorite(id: String) {
        if (id.isBlank()) return
        val next = _favorites.value.toMutableSet().apply {
            if (!add(id)) remove(id)
        }.toSet()
        _favorites.value = next
        preferences.edit().putStringSet(FAVORITES, next).apply()
    }

    fun recordOpened(id: String) {
        if (id.isBlank()) return
        val next = normalizeRecentIds(listOf(id) + _recent.value)
        _recent.value = next
        preferences.edit().putString(RECENT, next.joinToString("\n")).apply()
    }
}
