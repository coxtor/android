package io.homeassistant.companion.android.settings.push

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.homeassistant.companion.android.frontend.permissions.FcmSupport
import io.homeassistant.companion.android.notifications.push.UnifiedPushDistributor
import io.homeassistant.companion.android.notifications.push.UnifiedPushManager
import io.homeassistant.companion.android.notifications.push.UnifiedPushState
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * @property firebaseAvailable Whether this build can use Firebase at all.
 * @property unifiedPushSelected Whether UnifiedPush is the registered transport.
 */
internal data class CloudPushViewState(
    val firebaseAvailable: Boolean,
    val unifiedPushSelected: Boolean = false,
    val distributors: List<UnifiedPushDistributor> = emptyList(),
    val registration: UnifiedPushState = UnifiedPushState.Disabled,
)

@HiltViewModel
internal class CloudPushViewModel @Inject constructor(
    private val unifiedPushManager: UnifiedPushManager,
    @FcmSupport firebaseAvailable: Boolean,
) : ViewModel() {

    private val _viewState = MutableStateFlow(CloudPushViewState(firebaseAvailable = firebaseAvailable))
    val viewState: StateFlow<CloudPushViewState> = _viewState.asStateFlow()

    init {
        viewModelScope.launch {
            unifiedPushManager.state.collect { registration ->
                _viewState.update {
                    it.copy(
                        registration = registration,
                        unifiedPushSelected = registration != UnifiedPushState.Disabled,
                    )
                }
            }
        }
        refresh()
    }

    /** Reads the distributors and the registration again, for example after returning to the screen. */
    fun refresh() {
        viewModelScope.launch {
            unifiedPushManager.refresh()
            val distributors = unifiedPushManager.distributors()
            _viewState.update { it.copy(distributors = distributors) }
        }
    }

    fun onFirebaseSelected() {
        viewModelScope.launch { unifiedPushManager.disable() }
    }

    fun onDistributorSelected(packageName: String) {
        viewModelScope.launch {
            unifiedPushManager.enable(packageName)
            _viewState.update { state ->
                state.copy(
                    distributors = state.distributors.map { it.copy(selected = it.packageName == packageName) },
                )
            }
        }
    }
}
