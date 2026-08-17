package com.voidnullvalue.icseelocal.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler

/**
 * Explicit back stack over [Screen]. System back and toolbar back both [pop];
 * the Activity only finishes when the stack is a single [Screen.CameraList].
 */
class NavStack(initial: Screen = Screen.CameraList) {
    var stack by mutableStateOf(listOf(initial))
        private set

    val current: Screen get() = stack.last()

    fun push(screen: Screen) {
        if (screen == current) return
        stack = stack + screen
    }

    /** Replace the top of the stack (e.g. BlePairing → CameraSettings). */
    fun replaceTop(screen: Screen) {
        stack = if (stack.isEmpty()) listOf(screen) else stack.dropLast(1) + screen
    }

    /** @return true if a screen was popped; false if already at root (caller may finish Activity). */
    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack = stack.dropLast(1)
        return true
    }

    fun popTo(predicate: (Screen) -> Boolean): Boolean {
        val idx = stack.indexOfLast(predicate)
        if (idx < 0) return false
        stack = stack.take(idx + 1)
        return true
    }
}

@Composable
fun rememberNavStack(initial: Screen = Screen.CameraList): NavStack = remember { NavStack(initial) }

/**
 * Handles system back: pop the [NavStack], or invoke [onRootBack] (typically
 * finish the Activity) when already on the root screen.
 */
@Composable
fun NavBackHandler(nav: NavStack, onRootBack: () -> Unit) {
    BackHandler {
        if (!nav.pop()) onRootBack()
    }
}
