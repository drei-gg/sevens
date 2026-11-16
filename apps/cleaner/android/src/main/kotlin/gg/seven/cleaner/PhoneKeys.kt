package gg.seven.cleaner

import com.google.i18n.phonenumbers.PhoneNumberUtil

/**
 * Phone match keys (iOS PhoneNormalizer over PhoneNumberKit; both carry Google's libphonenumber
 * metadata). Without a chosen country only international numbers (with `+`) get a key. A number
 * the metadata doesn't consider valid gets none, as PhoneNumberKit's parse refuses it.
 */
class PhoneNormalizer {
    private val utility = PhoneNumberUtil.getInstance()

    fun normalized(raw: String, region: String?): String? {
        val text = raw.trim()
        if (!text.startsWith("+") && region == null) return null
        // With '+', the calling code wins; US is only the parser's initial territory, not an inference.
        val number = runCatching { utility.parse(text, region ?: "US") }.getOrNull() ?: return null
        if (!utility.isValidNumber(number)) return null
        val extension = number.extension.takeIf { number.hasExtension() && it.isNotEmpty() }
        return utility.format(number, PhoneNumberUtil.PhoneNumberFormat.E164) + (extension?.let { ";ext=$it" } ?: "")
    }
}
