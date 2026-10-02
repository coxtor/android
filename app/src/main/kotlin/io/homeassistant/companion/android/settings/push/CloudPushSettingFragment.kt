package io.homeassistant.companion.android.settings.push

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.compose.theme.HATheme
import io.homeassistant.companion.android.settings.push.views.CloudPushSettingView

@AndroidEntryPoint
class CloudPushSettingFragment : Fragment() {

    private val viewModel: CloudPushViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setContent {
                HATheme {
                    val state by viewModel.viewState.collectAsStateWithLifecycle()
                    CloudPushSettingView(
                        state = state,
                        onFirebaseSelected = viewModel::onFirebaseSelected,
                        onDistributorSelected = viewModel::onDistributorSelected,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A distributor can be installed or removed while this screen is open.
        viewModel.refresh()
        activity?.title = getString(commonR.string.cloud_push_title)
    }
}
