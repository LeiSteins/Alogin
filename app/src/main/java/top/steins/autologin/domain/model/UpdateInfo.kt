package top.steins.autologin.domain.model

data class UpdateInfo(
    val version: String,
    val fileName: String,
    val downloadUrl: String,
    val versionCode: Int,
    val releaseNotes: String = ""
)
