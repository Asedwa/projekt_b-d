package org.kde.plasmawave.player

sealed interface SleepTimerState {
    data object Off : SleepTimerState
    data class Countdown(val endElapsedMs: Long, val remainingMs: Long) : SleepTimerState
    data object EndOfTrack : SleepTimerState
}

fun SleepTimerState.isActive(): Boolean = this !is SleepTimerState.Off

fun formatSleepTimerRemaining(remainingMs: Long): String {
    val totalSeconds = ((remainingMs.coerceAtLeast(0L) + 999L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

fun SleepTimerState.summaryLabel(): String? = when (this) {
    SleepTimerState.Off -> null
    is SleepTimerState.Countdown -> formatSleepTimerRemaining(remainingMs)
    SleepTimerState.EndOfTrack -> "Şarkı bitince"
}
