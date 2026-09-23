package app.flipsilence

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import java.util.Locale

/**
 * The languages Flip is written in, and the app's own choice among them over the system's.
 *
 * Android keeps the choice per app and recreates the open activities when it changes, the same
 * way it does for the theme, so nothing is stored in [Prefs] and nothing is reapplied at launch.
 * Keep [ALL] in step with the values-* folders and res/xml/locales_config.xml.
 */
internal data class AppLanguage(val tag: String, val nativeName: String) {

    companion object {
        /** Sorted by how each language writes its own name, as Android's own picker does. */
        val ALL = listOf(
            AppLanguage("in", "Bahasa Indonesia"),
            AppLanguage("de", "Deutsch"),
            AppLanguage("en", "English"),
            AppLanguage("es", "Español"),
            AppLanguage("fr", "Français"),
            AppLanguage("it", "Italiano"),
            AppLanguage("pt-BR", "Português"),
            AppLanguage("tr", "Türkçe"),
            AppLanguage("ru", "Русский"),
            AppLanguage("uk", "Українська"),
            AppLanguage("ja", "日本語"),
            AppLanguage("ko", "한국어"),
        )

        private fun of(locale: Locale): AppLanguage? {
            // Java still spells Indonesian "in" on some paths and "id" on others.
            val language = if (locale.language == "id") "in" else locale.language
            return ALL.firstOrNull { it.tag.substringBefore('-') == language }
        }

        /** The language picked for Flip, or null when it follows the system. */
        fun chosen(ctx: Context): AppLanguage? =
            ctx.getSystemService(LocaleManager::class.java).applicationLocales
                .takeUnless { it.isEmpty }?.get(0)?.let(::of)

        /**
         * What following the system comes to: the first of the phone's languages Flip is written
         * in, else English, which is what Android's resource lookup would pick.
         */
        fun fromSystem(ctx: Context): AppLanguage {
            val system = ctx.getSystemService(LocaleManager::class.java).systemLocales
            return (0 until system.size()).firstNotNullOfOrNull { of(system[it]) } ?: ALL.first { it.tag == "en" }
        }

        /** Null goes back to the system's language. */
        fun choose(ctx: Context, language: AppLanguage?) {
            ctx.getSystemService(LocaleManager::class.java).applicationLocales =
                if (language == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language.tag)
        }
    }

    /** The name in the language Flip is showing now, for anyone who cannot read [nativeName]. */
    fun localName(ctx: Context): String {
        val shown = ctx.resources.configuration.locales[0]
        return Locale.forLanguageTag(tag).getDisplayLanguage(shown).replaceFirstChar { it.titlecase(shown) }
    }
}
