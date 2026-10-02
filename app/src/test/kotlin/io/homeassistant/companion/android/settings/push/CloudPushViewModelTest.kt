package io.homeassistant.companion.android.settings.push

import io.homeassistant.companion.android.notifications.push.UnifiedPushDistributor
import io.homeassistant.companion.android.notifications.push.UnifiedPushManager
import io.homeassistant.companion.android.notifications.push.UnifiedPushState
import io.homeassistant.companion.android.testing.unit.MainDispatcherJUnit5Extension
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

private const val DISTRIBUTOR = "io.heckel.ntfy"

@ExtendWith(MainDispatcherJUnit5Extension::class)
class CloudPushViewModelTest {

    private val unifiedPushManager = mockk<UnifiedPushManager>(relaxed = true)
    private val state = MutableStateFlow<UnifiedPushState>(UnifiedPushState.Disabled)

    @BeforeEach
    fun setUp() {
        every { unifiedPushManager.state } returns state
        coEvery { unifiedPushManager.distributors() } returns listOf(
            UnifiedPushDistributor(DISTRIBUTOR, "ntfy", selected = false),
        )
    }

    private fun viewModel(firebaseAvailable: Boolean) = CloudPushViewModel(unifiedPushManager, firebaseAvailable)

    @Test
    fun `Given a build with Firebase when opening the screen then Firebase is offered and selected`() = runTest {
        val viewModel = viewModel(firebaseAvailable = true)
        advanceUntilIdle()

        val viewState = viewModel.viewState.value
        assertTrue(viewState.firebaseAvailable)
        assertFalse(viewState.unifiedPushSelected)
        assertEquals(listOf(UnifiedPushDistributor(DISTRIBUTOR, "ntfy", selected = false)), viewState.distributors)
    }

    @Test
    fun `Given a build without Firebase when opening the screen then only distributors are offered`() = runTest {
        val viewModel = viewModel(firebaseAvailable = false)
        advanceUntilIdle()

        assertFalse(viewModel.viewState.value.firebaseAvailable)
        assertEquals(1, viewModel.viewState.value.distributors.size)
    }

    @Test
    fun `Given Firebase when picking a distributor then it becomes the selected transport`() = runTest {
        val viewModel = viewModel(firebaseAvailable = true)
        advanceUntilIdle()

        viewModel.onDistributorSelected(DISTRIBUTOR)
        state.value = UnifiedPushState.Registered(temporary = false)
        advanceUntilIdle()

        coVerify { unifiedPushManager.enable(DISTRIBUTOR) }
        assertTrue(viewModel.viewState.value.unifiedPushSelected)
        assertTrue(viewModel.viewState.value.distributors.single().selected)
    }

    @Test
    fun `Given UnifiedPush when picking Firebase then it hands the registration back`() = runTest {
        state.value = UnifiedPushState.Registered(temporary = false)
        val viewModel = viewModel(firebaseAvailable = true)
        advanceUntilIdle()
        assertTrue(viewModel.viewState.value.unifiedPushSelected)

        viewModel.onFirebaseSelected()
        state.value = UnifiedPushState.Disabled
        advanceUntilIdle()

        coVerify { unifiedPushManager.disable() }
        assertFalse(viewModel.viewState.value.unifiedPushSelected)
    }

    @Test
    fun `Given a temporary endpoint when the state changes then the screen reports it`() = runTest {
        val viewModel = viewModel(firebaseAvailable = true)
        advanceUntilIdle()

        state.value = UnifiedPushState.Registered(temporary = true)
        advanceUntilIdle()

        assertEquals(UnifiedPushState.Registered(temporary = true), viewModel.viewState.value.registration)
    }

    @Test
    fun `Given no distributor when opening the screen then none is offered`() = runTest {
        coEvery { unifiedPushManager.distributors() } returns emptyList()

        val viewModel = viewModel(firebaseAvailable = false)
        advanceUntilIdle()

        assertTrue(viewModel.viewState.value.distributors.isEmpty())
    }
}
