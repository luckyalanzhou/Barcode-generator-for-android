package com.luckyalanzhou.barcodegenerator

import androidx.lifecycle.ViewModel

class BarcodeViewModel : ViewModel() {
    var page: String = "generate"
    var inputDraft: MutableList<String> = mutableListOf()
    var pendingGenerateFormat: String? = null
    var resultItems: List<CodeItem> = emptyList()
    var showingHistoryResult: Boolean = false
    var resultsReturnPage: String = "generate"
    var selectedFavoriteGroup: FavoriteGroup? = null
    var collapsedFavoriteFolders: MutableSet<String> = mutableSetOf()
    var favoriteTreeInitialized: Boolean = false
    var settingsReturnPage: String = "generate"
    var startupUpdateCheckStarted: Boolean = false
    var availableUpdateUrl: String? = null
    var availableUpdateExpectedSize: Long? = null
    var availableUpdateSha256: String? = null
    var updateDialogShowing: Boolean = false
    var updateDownloadRunning: Boolean = false
    var pendingInstallPath: String? = null
}
