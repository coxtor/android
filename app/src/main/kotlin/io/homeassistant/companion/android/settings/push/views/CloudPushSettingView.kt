package io.homeassistant.companion.android.settings.push.views

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.compose.composable.HAHint
import io.homeassistant.companion.android.common.compose.composable.HARadioGroup
import io.homeassistant.companion.android.common.compose.composable.RadioOption
import io.homeassistant.companion.android.common.compose.theme.HADimens
import io.homeassistant.companion.android.common.compose.theme.HATextStyle
import io.homeassistant.companion.android.common.compose.theme.HAThemeForPreview
import io.homeassistant.companion.android.common.compose.theme.LocalHAColorScheme
import io.homeassistant.companion.android.notifications.push.UnifiedPushDistributor
import io.homeassistant.companion.android.notifications.push.UnifiedPushState
import io.homeassistant.companion.android.settings.push.CloudPushViewState
import io.homeassistant.companion.android.util.plus
import io.homeassistant.companion.android.util.safeBottomPaddingValues
import org.unifiedpush.android.connector.FailedReason

/**
 * What the user can pick on this screen, so that Firebase and the distributors form a single
 * selection instead of separate ones.
 */
private sealed interface CloudPushOption {
    data object Firebase : CloudPushOption
    data class Distributor(val packageName: String) : CloudPushOption
}

/**
 * Lets the user choose where Home Assistant sends notifications when it cannot use the WebSocket
 * connection. Neither the endpoint nor its token is shown, both identify this device.
 */
@Composable
internal fun CloudPushSettingView(
    state: CloudPushViewState,
    onFirebaseSelected: () -> Unit,
    onDistributorSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = LocalHAColorScheme.current
    val options = rememberCloudPushOptions(state)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(all = HADimens.SPACE4) + safeBottomPaddingValues(applyHorizontal = false)),
        verticalArrangement = Arrangement.spacedBy(HADimens.SPACE4),
    ) {
        Text(
            text = stringResource(commonR.string.cloud_push_description),
            style = HATextStyle.Body,
            color = colorScheme.colorTextPrimary,
        )

        state.registration.actionableWarning()?.let { warning ->
            HAHint(text = stringResource(warning), modifier = Modifier.fillMaxWidth())
        }

        if (options.isNotEmpty()) {
            HARadioGroup(
                options = options,
                onSelect = { option ->
                    when (val selected = option.selectionKey) {
                        CloudPushOption.Firebase -> onFirebaseSelected()
                        is CloudPushOption.Distributor -> onDistributorSelected(selected.packageName)
                    }
                },
                selectionKey = state.selectedOption(),
            )
        }

        if (state.distributors.isEmpty()) {
            Text(
                text = stringResource(commonR.string.cloud_push_no_distributor_installed),
                style = HATextStyle.BodyMedium,
                color = colorScheme.colorTextSecondary,
            )
        }

        Text(
            text = stringResource(state.registration.statusMessage(state.firebaseAvailable)),
            style = HATextStyle.BodyMedium,
            color = colorScheme.colorTextSecondary,
        )
    }
}

/** The rows of the radio group, Firebase first when this build has it, then every distributor. */
@Composable
private fun rememberCloudPushOptions(state: CloudPushViewState): List<RadioOption<CloudPushOption>> {
    val firebaseLabel = stringResource(commonR.string.cloud_push_firebase)
    return remember(state.firebaseAvailable, state.distributors, firebaseLabel) {
        buildList {
            if (state.firebaseAvailable) {
                add(
                    RadioOption<CloudPushOption>(
                        selectionKey = CloudPushOption.Firebase,
                        headline = firebaseLabel,
                    ),
                )
            }
            state.distributors.forEach { distributor ->
                add(
                    RadioOption<CloudPushOption>(
                        selectionKey = CloudPushOption.Distributor(distributor.packageName),
                        headline = distributor.label,
                    ),
                )
            }
        }
    }
}

/** The option the radio group marks as selected, `null` while the choice has no row to show. */
private fun CloudPushViewState.selectedOption(): CloudPushOption? = if (unifiedPushSelected) {
    distributors.firstOrNull { it.selected }?.let { CloudPushOption.Distributor(it.packageName) }
} else {
    CloudPushOption.Firebase.takeIf { firebaseAvailable }
}

/** A warning only for the states the user has to resolve, the rest is handled in the background. */
@StringRes
private fun UnifiedPushState.actionableWarning(): Int? = when (this) {
    is UnifiedPushState.Failed -> when (reason) {
        FailedReason.ACTION_REQUIRED -> commonR.string.cloud_push_error_action_required
        FailedReason.VAPID_REQUIRED -> commonR.string.cloud_push_error_vapid_required
        FailedReason.NETWORK, FailedReason.INTERNAL_ERROR -> null
    }
    UnifiedPushState.NoDistributor -> commonR.string.cloud_push_state_no_distributor
    UnifiedPushState.Disabled,
    UnifiedPushState.Registering,
    UnifiedPushState.Unavailable,
    is UnifiedPushState.Registered,
    -> null
}

/**
 * @param firebaseAvailable Whether this build can use Firebase, because without it
 * [UnifiedPushState.Disabled] means that no cloud push transport is left rather than that Firebase
 * took over.
 */
@StringRes
private fun UnifiedPushState.statusMessage(firebaseAvailable: Boolean): Int = when (this) {
    UnifiedPushState.Disabled -> if (firebaseAvailable) {
        commonR.string.cloud_push_state_firebase
    } else {
        commonR.string.cloud_push_state_no_transport
    }
    UnifiedPushState.NoDistributor -> commonR.string.cloud_push_state_no_distributor
    UnifiedPushState.Registering -> commonR.string.cloud_push_state_registering
    UnifiedPushState.Unavailable -> commonR.string.cloud_push_state_unavailable
    is UnifiedPushState.Registered -> if (temporary) {
        commonR.string.cloud_push_state_registered_temporary
    } else {
        commonR.string.cloud_push_state_registered
    }
    is UnifiedPushState.Failed -> commonR.string.cloud_push_state_failed
}

@Preview
@Composable
private fun CloudPushSettingViewFirebasePreview() {
    HAThemeForPreview {
        CloudPushSettingView(
            state = CloudPushViewState(
                firebaseAvailable = true,
                unifiedPushSelected = false,
                distributors = listOf(
                    UnifiedPushDistributor(packageName = "io.heckel.ntfy", label = "ntfy", selected = false),
                ),
                registration = UnifiedPushState.Disabled,
            ),
            onFirebaseSelected = {},
            onDistributorSelected = {},
        )
    }
}

@Preview
@Composable
private fun CloudPushSettingViewRegisteredPreview() {
    HAThemeForPreview {
        CloudPushSettingView(
            state = CloudPushViewState(
                firebaseAvailable = false,
                unifiedPushSelected = true,
                distributors = listOf(
                    UnifiedPushDistributor(packageName = "io.heckel.ntfy", label = "ntfy", selected = true),
                ),
                registration = UnifiedPushState.Registered(temporary = false),
            ),
            onFirebaseSelected = {},
            onDistributorSelected = {},
        )
    }
}

@Preview
@Composable
private fun CloudPushSettingViewNoDistributorPreview() {
    HAThemeForPreview {
        CloudPushSettingView(
            state = CloudPushViewState(
                firebaseAvailable = false,
                unifiedPushSelected = true,
                distributors = emptyList(),
                registration = UnifiedPushState.NoDistributor,
            ),
            onFirebaseSelected = {},
            onDistributorSelected = {},
        )
    }
}
