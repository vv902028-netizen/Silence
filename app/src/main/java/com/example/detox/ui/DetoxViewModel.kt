package com.example.detox.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.detox.data.CompletedSessionSummary
import com.example.detox.data.DetoxMode
import com.example.detox.data.DetoxSessionRepository
import com.example.detox.data.ProtectionStatus
import com.example.detox.service.DetoxMonitorService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface DetoxScreen {
    data object Home : DetoxScreen
    data object ShieldSetup : DetoxScreen
    data object ActiveLock : DetoxScreen
    data object Completed : DetoxScreen
}

data class DetoxUiState(
    val screen: DetoxScreen = DetoxScreen.Home,
    val selectedDurationMinutes: Int = DetoxSessionRepository.DEFAULT_DURATION_MINUTES,
    val selectedMode: DetoxMode = DetoxMode.PHONE_ONLY,
    val showConfirmSheet: Boolean = false,
    val estimatedEndWallMs: Long = System.currentTimeMillis() + DetoxSessionRepository.DEFAULT_DURATION_MINUTES * 60_000L,
    val activeMode: DetoxMode = DetoxMode.PHONE_ONLY,
    val remainingMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val plannedEndWallMs: Long = 0L,
    val timeTamperDetected: Boolean = false,
    val bypassAttempts: Int = 0,
    val isScreenPinned: Boolean = false,
    val blockNotificationShade: Boolean = false,
    val blockControlCenter: Boolean = false,
    val protectionStatus: ProtectionStatus = ProtectionStatus(),
    val completedSummary: CompletedSessionSummary? = null
) {
    val progressRemainingFraction: Float
        get() = if (totalDurationMs > 0L) {
            (remainingMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

class DetoxViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DetoxSessionRepository.getInstance(application)

    private val _uiState = MutableStateFlow(buildInitialState())
    val uiState: StateFlow<DetoxUiState> = _uiState.asStateFlow()

    init {
        observeRepository()
        startClockTicker()
    }

    private fun buildInitialState(): DetoxUiState {
        repository.reconcileAfterReboot()
        val snap = repository.getCurrentSnapshot()
        val protection = repository.refreshProtectionStatus()
        val prefDuration = repository.readPreferredDurationMinutes()
        val prefMode = repository.readPreferredMode()
        val remaining = if (snap.isActive) repository.computeRemainingMs() else 0L
        val activeNow = snap.isActive && remaining > 0L

        if (snap.isActive && remaining <= 0L) {
            repository.completeSession()
        }

        val initialScreen = when {
            activeNow -> DetoxScreen.ActiveLock
            repository.lastCompletedFlow.value != null -> DetoxScreen.Completed
            else -> DetoxScreen.Home
        }

        return DetoxUiState(
            screen = initialScreen,
            selectedDurationMinutes = prefDuration,
            selectedMode = prefMode,
            estimatedEndWallMs = System.currentTimeMillis() + prefDuration * 60_000L,
            activeMode = snap.mode,
            remainingMs = remaining,
            totalDurationMs = snap.totalDurationMs,
            plannedEndWallMs = snap.plannedEndWallMs,
            timeTamperDetected = snap.timeTamperDetected,
            bypassAttempts = snap.bypassAttempts,
            blockNotificationShade = repository.readBlockNotificationShade(),
            blockControlCenter = repository.readBlockControlCenter(),
            protectionStatus = protection,
            completedSummary = repository.lastCompletedFlow.value
        )
    }

    private fun observeRepository() {
        viewModelScope.launch {
            repository.sessionFlow.collect { snap ->
                val remaining = if (snap.isActive) repository.computeRemainingMs() else 0L
                val activeNow = snap.isActive && remaining > 0L
                _uiState.update { current ->
                    val nextScreen = when {
                        activeNow -> DetoxScreen.ActiveLock
                        current.completedSummary != null || repository.lastCompletedFlow.value != null ->
                            DetoxScreen.Completed
                        current.screen == DetoxScreen.ShieldSetup -> DetoxScreen.ShieldSetup
                        else -> DetoxScreen.Home
                    }
                    current.copy(
                        screen = nextScreen,
                        showConfirmSheet = if (activeNow) false else current.showConfirmSheet,
                        activeMode = snap.mode,
                        remainingMs = remaining,
                        totalDurationMs = snap.totalDurationMs,
                        plannedEndWallMs = snap.plannedEndWallMs,
                        timeTamperDetected = snap.timeTamperDetected,
                        bypassAttempts = snap.bypassAttempts
                    )
                }
            }
        }

        viewModelScope.launch {
            repository.protectionFlow.collect { status ->
                _uiState.update { it.copy(protectionStatus = status) }
            }
        }

        viewModelScope.launch {
            repository.blockShadeFlow.collect { enabled ->
                _uiState.update { it.copy(blockNotificationShade = enabled) }
            }
        }

        viewModelScope.launch {
            repository.blockControlCenterFlow.collect { enabled ->
                _uiState.update { it.copy(blockControlCenter = enabled) }
            }
        }

        viewModelScope.launch {
            repository.lastCompletedFlow.collect { summary ->
                _uiState.update { current ->
                    val nextScreen = when {
                        repository.isSessionActive() -> DetoxScreen.ActiveLock
                        summary != null -> DetoxScreen.Completed
                        current.screen == DetoxScreen.Completed -> DetoxScreen.Home
                        else -> current.screen
                    }
                    current.copy(
                        screen = nextScreen,
                        completedSummary = summary
                    )
                }
            }
        }
    }

    private fun startClockTicker() {
        viewModelScope.launch {
            while (isActive) {
                val state = _uiState.value
                if (state.screen == DetoxScreen.ActiveLock) {
                    val remaining = repository.computeRemainingMs()
                    if (remaining <= 0L) {
                        repository.completeSession()
                        DetoxMonitorService.cancelAllAlarms(getApplication())
                        DetoxMonitorService.stop(getApplication())
                    } else {
                        _uiState.update { it.copy(remainingMs = remaining) }
                    }
                } else {
                    val updatedEnd = System.currentTimeMillis() + state.selectedDurationMinutes * 60_000L
                    _uiState.update { it.copy(estimatedEndWallMs = updatedEnd) }
                }
                delay(500L)
            }
        }
    }

    fun selectDurationMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(
            DetoxSessionRepository.MIN_DURATION_MINUTES,
            DetoxSessionRepository.MAX_DURATION_MINUTES
        )
        repository.savePreferredConfig(clamped, _uiState.value.selectedMode)
        _uiState.update {
            it.copy(
                selectedDurationMinutes = clamped,
                estimatedEndWallMs = System.currentTimeMillis() + clamped * 60_000L
            )
        }
    }

    fun adjustDurationBy(deltaMinutes: Int) {
        selectDurationMinutes(_uiState.value.selectedDurationMinutes + deltaMinutes)
    }

    fun selectMode(mode: DetoxMode) {
        repository.savePreferredConfig(_uiState.value.selectedDurationMinutes, mode)
        _uiState.update { it.copy(selectedMode = mode) }
    }

    fun openShieldSetup() {
        if (repository.isSessionActive()) return
        repository.refreshProtectionStatus()
        _uiState.update { it.copy(screen = DetoxScreen.ShieldSetup, showConfirmSheet = false) }
    }

    fun closeShieldSetup() {
        if (repository.isSessionActive()) return
        _uiState.update { it.copy(screen = DetoxScreen.Home) }
    }

    fun requestStartConfirmation() {
        val status = repository.refreshProtectionStatus()
        if (!status.requiredReady) {
            _uiState.update { it.copy(screen = DetoxScreen.ShieldSetup) }
        } else {
            _uiState.update {
                it.copy(
                    showConfirmSheet = true,
                    estimatedEndWallMs = System.currentTimeMillis() + it.selectedDurationMinutes * 60_000L
                )
            }
        }
    }

    fun dismissConfirmSheet() {
        _uiState.update { it.copy(showConfirmSheet = false) }
    }

    fun confirmAndStartSession() {
        val state = _uiState.value
        repository.startSession(
            durationMinutes = state.selectedDurationMinutes,
            mode = state.selectedMode
        )
        DetoxMonitorService.start(getApplication())
        _uiState.update { it.copy(showConfirmSheet = false, screen = DetoxScreen.ActiveLock) }
    }

    fun dismissCompletedSummary() {
        repository.clearCompletedSummary()
        _uiState.update { it.copy(screen = DetoxScreen.Home, completedSummary = null) }
    }

    fun onHostResumed(isPinned: Boolean) {
        repository.reconcileAfterReboot()
        repository.refreshProtectionStatus()
        val isActiveNow = repository.isSessionActive()
        if (isActiveNow && !DetoxMonitorService.isRunning) {
            DetoxMonitorService.start(getApplication())
        }
        _uiState.update { current ->
            current.copy(
                isScreenPinned = isPinned,
                remainingMs = if (isActiveNow) repository.computeRemainingMs() else 0L
            )
        }
    }

    fun updatePinnedState(isPinned: Boolean) {
        _uiState.update { it.copy(isScreenPinned = isPinned) }
    }

    fun toggleBlockNotificationShade(enabled: Boolean) {
        repository.setBlockNotificationShade(enabled)
    }

    fun toggleBlockControlCenter(enabled: Boolean) {
        repository.setBlockControlCenter(enabled)
    }

    fun disableDeviceAdminIfIdle() {
        repository.disableDeviceAdminIfIdle()
        scheduleDelayedProtectionRefresh()
    }

    fun disableAccessibilityIfIdle(): Boolean {
        val disabledDirectly = repository.disableAccessibilityIfIdle()
        scheduleDelayedProtectionRefresh()
        return disabledDirectly
    }

    fun revokeAllProtectionIfIdle() {
        repository.revokeAllProtectionIfIdle()
        scheduleDelayedProtectionRefresh()
    }

    private fun scheduleDelayedProtectionRefresh() {
        viewModelScope.launch {
            delay(350L)
            repository.refreshProtectionStatus()
            delay(650L)
            repository.refreshProtectionStatus()
        }
    }
}
