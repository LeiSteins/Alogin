package top.steins.autologin.state

/** 每次应用生命周期只有一次启动登录机会；运行中重新开启设置不会补发登录。 */
internal class StartupAutoLoginPolicy(enabledAtStartup: Boolean) {
    private var pending = enabledAtStartup

    fun cancel() {
        pending = false
    }

    fun consume(enabled: Boolean, hasPermission: Boolean, hasCredentials: Boolean): Boolean {
        if (!pending) return false
        pending = false
        return enabled && hasPermission && hasCredentials
    }
}
