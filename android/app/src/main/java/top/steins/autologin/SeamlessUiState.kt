package top.steins.autologin

import top.steins.autologin.domain.model.SeamlessInfo

data class SeamlessUiState(
    val info: SeamlessInfo? = null,
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: String = "",
    val operationMessage: String = ""
) {
    // 已有数据时，后台刷新仍允许打开操作弹窗；实际提交需等待刷新完成。
    val canPrepareAction: Boolean get() = info != null && !isSubmitting && error.isBlank()
    val canManage: Boolean get() = canPrepareAction && !isLoading
}
