package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources

/**
 * CloudStream plugins extend `com.lagradost.cloudstream3.plugins.Plugin`,
 * which lives in the CloudStream *app* module — not in the `library` AAR
 * StreamBox links against. Without a class of this exact name the plugin
 * cannot even be instantiated, so StreamBox provides the minimal host
 * contract here: `load(Context)`, `resources`, `openSettings`.
 *
 * UNVERIFIED against the current library: if the experimental build fails
 * with "Redeclaration: Plugin" the library already ships this class —
 * delete this file.
 */
abstract class Plugin : BasePlugin() {
    @Throws(Throwable::class)
    open fun load(context: Context) {
    }

    var resources: Resources? = null
    var openSettings: ((context: Context) -> Unit)? = null
}
