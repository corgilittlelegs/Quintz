package com.quintz.wifi.service

/** Matches the exact custom tile component, including Android's short class form. */
internal object QuickTileSpec {
    fun matches(spec: String, packageName: String, className: String): Boolean {
        val value = spec.trim()
        if (!value.startsWith("custom(") || !value.endsWith(")")) return false
        val component = value.substring(7, value.length - 1)
        val separator = component.indexOf('/')
        if (separator <= 0) return false
        val owner = component.substring(0, separator)
        val service = component.substring(separator + 1)
        val expanded = if (service.startsWith(".")) owner + service else service
        return owner == packageName && expanded == className
    }
}
