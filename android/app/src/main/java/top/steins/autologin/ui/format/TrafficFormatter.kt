package top.steins.autologin.ui.format

import java.text.DecimalFormat

fun formatCompactTrafficMegabytes(trafficMegabytes: Double): String {
    val gigabytes = trafficMegabytes >= 1024.0
    val value = if (gigabytes) trafficMegabytes / 1024.0 else trafficMegabytes
    return DecimalFormat("0.#").format(value) + if (gigabytes) "G" else "M"
}
