/* Copyright 2026 Tusky Contributors. GPL-3.0-or-later. */
package com.keylesspalace.tusky.components.preference

import android.os.Bundle
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import com.keylesspalace.tusky.R
import com.keylesspalace.tusky.settings.ALT_TEXT_DEFAULT_BASE_URL
import com.keylesspalace.tusky.settings.ALT_TEXT_DEFAULT_MAX_TOKENS
import com.keylesspalace.tusky.settings.ALT_TEXT_DEFAULT_MODEL
import com.keylesspalace.tusky.settings.ALT_TEXT_DEFAULT_PROMPT
import com.keylesspalace.tusky.settings.PrefKeys
import com.keylesspalace.tusky.settings.editTextPreference
import com.keylesspalace.tusky.settings.makePreferenceScreen

class AltTextPreferencesFragment : BasePreferencesFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        makePreferenceScreen {
            editTextPreference {
                key = PrefKeys.ALT_TEXT_API_KEY
                setTitle(R.string.pref_title_alt_text_api_key)
                isIconSpaceReserved = false
                setOnBindEditTextListener { it.inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    if (pref.text.isNullOrBlank()) {
                        getString(R.string.pref_summary_alt_text_api_key_not_set)
                    } else {
                        getString(R.string.pref_summary_alt_text_api_key_set)
                    }
                }
            }

            editTextPreference {
                key = PrefKeys.ALT_TEXT_BASE_URL
                setTitle(R.string.pref_title_alt_text_base_url)
                isIconSpaceReserved = false
                setDefaultValue(ALT_TEXT_DEFAULT_BASE_URL)
                setOnBindEditTextListener { it.inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            }

            editTextPreference {
                key = PrefKeys.ALT_TEXT_MODEL
                setTitle(R.string.pref_title_alt_text_model)
                isIconSpaceReserved = false
                setDefaultValue(ALT_TEXT_DEFAULT_MODEL)
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            }

            editTextPreference {
                key = PrefKeys.ALT_TEXT_PROMPT
                setTitle(R.string.pref_title_alt_text_prompt)
                isIconSpaceReserved = false
                setDefaultValue(ALT_TEXT_DEFAULT_PROMPT)
                setOnBindEditTextListener { editText ->
                    editText.inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    editText.minLines = 3
                    editText.maxLines = 10
                }
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    pref.text?.takeIf { it.isNotBlank() }
                        ?.let { if (it.length > 80) it.substring(0, 80) + "…" else it }
                        ?: ALT_TEXT_DEFAULT_PROMPT
                }
            }

            editTextPreference {
                key = PrefKeys.ALT_TEXT_MAX_TOKENS
                setTitle(R.string.pref_title_alt_text_max_tokens)
                isIconSpaceReserved = false
                setDefaultValue(ALT_TEXT_DEFAULT_MAX_TOKENS.toString())
                setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    pref.text?.toIntOrNull()?.toString() ?: ALT_TEXT_DEFAULT_MAX_TOKENS.toString()
                }
                setOnPreferenceChangeListener { _, newValue ->
                    val asString = newValue?.toString().orEmpty()
                    asString.isBlank() || asString.toIntOrNull()?.let { it > 0 } == true
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requireActivity().setTitle(R.string.pref_title_alt_text_generation)
    }

    object SummaryProvider : Preference.SummaryProvider<Preference> {
        override fun provideSummary(preference: Preference): CharSequence {
            val sp = preference.sharedPreferences ?: return ""
            val key = sp.getString(PrefKeys.ALT_TEXT_API_KEY, "").orEmpty()
            val model = sp.getString(PrefKeys.ALT_TEXT_MODEL, "").orEmpty()
            return if (key.isBlank() || model.isBlank()) {
                preference.context.getString(R.string.pref_summary_alt_text_not_configured)
            } else {
                model
            }
        }
    }

    companion object {
        fun newInstance(): AltTextPreferencesFragment = AltTextPreferencesFragment()
    }
}
