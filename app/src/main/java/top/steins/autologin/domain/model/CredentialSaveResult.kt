package top.steins.autologin.domain.model

/** 凭据保存结果；加密不可用时不落盘。 */
enum class CredentialSaveResult {
    SAVED,
    ENCRYPTION_UNAVAILABLE,
    STORAGE_UNAVAILABLE,
    INVALID_INPUT
}
