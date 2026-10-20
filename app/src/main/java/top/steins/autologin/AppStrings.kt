package top.steins.autologin

import androidx.annotation.StringRes

/** 字符串解析入口，让 ViewModel 不依赖 Android Context。 */
fun interface AppStrings {
    fun get(@StringRes resId: Int, vararg formatArgs: Any): String
}
