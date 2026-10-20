package top.steins.autologin

import top.steins.autologin.network.SeamlessInfo

data class SeamlessUiState(
    val info: SeamlessInfo? = null,
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: String = "",
    val operationMessage: String = ""
) {
    val canManage: Boolean get() = info != null && !isLoading && !isSubmitting && error.isBlank()
}
