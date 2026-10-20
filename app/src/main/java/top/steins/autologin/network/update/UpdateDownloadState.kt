package top.steins.autologin.network.update

import top.steins.autologin.domain.model.UpdateInfo

internal fun matchesUpdateArchive(
    packageName: String?,
    versionName: String?,
    versionCode: Long,
    expectedPackage: String,
    installedVersionCode: Long,
    update: UpdateInfo
): Boolean = packageName == expectedPackage && versionName == update.version &&
    versionCode == update.versionCode.toLong() && versionCode > installedVersionCode
