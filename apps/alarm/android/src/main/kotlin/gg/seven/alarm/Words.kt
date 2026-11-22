package gg.seven.alarm

import android.content.Context
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Words for code that runs outside a composition (the engine, receivers, the ringing service and
 * its notifications). Every word lives in res/values; this reads them through the application's
 * resources, which follow the device language. Screens use stringResource or LocalResources.
 */
object Words {
    /** Reads the application's resources (the application lives as long as the process). */
    @Volatile private var source: (() -> Resources)? = null

    fun install(context: Context) {
        val app = context.applicationContext
        source = { app.resources }
    }

    val resources: Resources
        get() = checkNotNull(source) { "Words.install runs in AlarmApplication.onCreate" }()

    fun get(@StringRes id: Int, vararg args: Any): String = resources.getString(id, *args)

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        resources.getQuantityString(id, count, *args)
}
