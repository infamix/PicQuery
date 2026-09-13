// FILE: app/src/main/java/me/grey/picquery/ui/search/SearchViewModel.kt
package me.grey.picquery.ui.search

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.grey.picquery.PicQueryApplication
import me.grey.picquery.R
import me.grey.picquery.common.showToast
import me.grey.picquery.data.data_source.PhotoRepository
import me.grey.picquery.data.model.Photo
import me.grey.picquery.domain.ImageSearcher
import timber.log.Timber

enum class SearchState {
    NO_INDEX,
    LOADING,
    READY,
    SEARCHING,
    FINISHED,
}

class SearchViewModel(
    private val imageSearcher: ImageSearcher,
    private val ioDispatcher: CoroutineDispatcher,
    private val repo: PhotoRepository
) : ViewModel() {
    companion object {
        private const val TAG = "SearchResultViewModel"
    }

    private val _resultList = MutableStateFlow<List<Photo>>(emptyList())
    val resultList = _resultList.asStateFlow()
    private val _resultMap = MutableStateFlow<Map<Long, Double>>(mutableMapOf())
    val resultMap: StateFlow<Map<Long, Double>> = _resultMap.asStateFlow()

    private val _searchState = MutableStateFlow(SearchState.LOADING)
    val searchState = _searchState.asStateFlow()

    private val _searchText = MutableStateFlow<String>("")
    val searchText: StateFlow<String> = _searchText.map { it }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ""
    )

    private var searchJob: Job? = null

    private val context: Context
        get() = PicQueryApplication.context

    init {
        Log.d(TAG, "init!!! SearchViewModel")
    }

    fun onQueryChange(query: String) {
        _searchState.value = SearchState.READY
        _searchText.value = query
    }

    fun startSearch(text: String) {
        if (text.trim().isEmpty()) {
            showToast(context.getString(R.string.empty_search_content_toast))
            Log.w(TAG, "搜索字段为空")
            return
        }
        _searchText.value = text
        searchJob?.cancel()
        searchJob = viewModelScope.launch(ioDispatcher) {
            _searchState.value = SearchState.SEARCHING
            try {
                val entries = imageSearcher.searchText(text)
                if (entries.isNotEmpty()) {
                    val ids = entries.map { it.value }
                    val photos = repo.getPhotoListByIds(ids)
                    _resultMap.update {
                        entries.associate { it.value to it.key }.toMutableMap()
                    }
                    _resultList.value = reOrderList(photos, ids)
                } else {
                    _resultList.value = emptyList()
                    _resultMap.value = emptyMap()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Search failed")
                _resultList.value = emptyList()
                _resultMap.value = emptyMap()
            } finally {
                if (isActive) {
                    _searchState.value = SearchState.FINISHED
                }
            }
        }
    }

    fun startSearch(uri: Uri) {
        val bitmap = repo.getBitmapFromUri(uri)
        if (bitmap == null) {
            showToast(context.getString(R.string.empty_search_content_toast))
            Log.w(TAG, "搜索字段为空")
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch(ioDispatcher) {
            _searchState.value = SearchState.SEARCHING
            try {
                val entries = imageSearcher.searchImage(bitmap)
                if (entries.isNotEmpty()) {
                    val ids = entries.map { it.value }
                    val photos = repo.getPhotoListByIds(ids)
                    _resultMap.update {
                        entries.associate { it.value to it.key }.toMutableMap()
                    }
                    _resultList.value = reOrderList(photos, ids)
                } else {
                    _resultList.value = emptyList()
                    _resultMap.value = emptyMap()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Image search failed")
                _resultList.value = emptyList()
                _resultMap.value = emptyMap()
            } finally {
                if (isActive) {
                    _searchState.value = SearchState.FINISHED
                }
            }
        }
    }

    private fun reOrderList(originalList: List<Photo>, orderList: List<Long>): List<Photo> {
        val photoMap = originalList.associateBy { it.id }
        return orderList.mapNotNull { id -> photoMap[id] }
    }
}