package io.homeassistant.companion.android.settings

import android.annotation.SuppressLint
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import io.homeassistant.companion.android.di.qualifiers.IsAutomotive
import io.homeassistant.companion.android.notifications.push.UnifiedPushManager
import io.homeassistant.companion.android.settings.assist.DefaultAssistantManager
import io.homeassistant.companion.android.settings.language.LanguagesProvider
import javax.inject.Inject

class SettingsFragmentFactory @Inject constructor(
    private val settingsPresenter: SettingsPresenter,
    private val languagesProvider: LanguagesProvider,
    private val defaultAssistantManager: DefaultAssistantManager,
    private val unifiedPushManager: UnifiedPushManager,
    @param:IsAutomotive private val isAutomotive: Boolean,
) : FragmentFactory() {
    @SuppressLint("NewApi")
    override fun instantiate(classLoader: ClassLoader, className: String): Fragment {
        return when (className) {
            SettingsFragment::class.java.name -> SettingsFragment(
                presenter = settingsPresenter,
                langProvider = languagesProvider,
                defaultAssistantManager = defaultAssistantManager,
                unifiedPushManager = unifiedPushManager,
                isAutomotive = isAutomotive,
            )
            else -> super.instantiate(classLoader, className)
        }
    }
}
