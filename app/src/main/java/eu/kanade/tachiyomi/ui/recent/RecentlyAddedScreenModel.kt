package eu.kanade.tachiyomi.ui.recent

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class RecentlyAddedScreenModel(
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
) : StateScreenModel<RecentlyAddedScreenModel.State>(State()) {

    init {
        screenModelScope.launch {
            getLibraryManga.subscribe().collectLatest { libraryMangas ->
                // Sort by dateAdded descending and take top 20
                val sortedMangas = libraryMangas
                    .sortedByDescending { it.manga.dateAdded }
                    .take(20)

                val categories = getCategories.await()
                
                val items = sortedMangas.map { libraryManga ->
                    val category = categories.find { it.id == libraryManga.categories.firstOrNull() }
                    val categoryName = category?.name ?: "Sin carpeta"
                    RecentlyAddedItem(libraryManga.manga, categoryName)
                }

                mutableState.update { it.copy(items = items, isLoading = false) }
            }
        }
    }

    @Immutable
    data class State(
        val items: List<RecentlyAddedItem> = emptyList(),
        val isLoading: Boolean = true,
    )
}

data class RecentlyAddedItem(
    val manga: Manga,
    val categoryName: String,
)
