package com.lumeter.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumeter.ui.screens.CalibrationPage
import com.lumeter.ui.screens.FilmPage
import com.lumeter.ui.screens.HistoryPage
import com.lumeter.ui.screens.MeterPage
import com.lumeter.ui.screens.SettingsPage
import com.lumeter.ui.screens.ToolsPage
import com.lumeter.ui.theme.LumenTheme

/**
 * Root application container: page-state navigation with a sheet-style transition,
 * themed by the user-selected accent.
 */
@Composable
fun LumeterApp(appViewModel: AppViewModel = viewModel()) {
    // System back walks the page hierarchy (calibration → settings → meter) instead of
    // exiting to the launcher; back on the meter page keeps the default exit behavior.
    val page = appViewModel.currentPage
    androidx.activity.compose.BackHandler(enabled = page != AppPage.METER) {
        val parent = if (page == AppPage.CALIBRATION) AppPage.SETTINGS else AppPage.METER
        appViewModel.navigateTo(parent)
    }
    LumenTheme(accent = appViewModel.accentColor) {
        Box(
            Modifier
                .fillMaxSize()
                .background(com.lumeter.ui.theme.ColorBody),
        ) {
            val page = appViewModel.currentPage
            androidx.compose.animation.AnimatedContent(
                targetState = page,
                transitionSpec = {
                    if (targetState == AppPage.METER) {
                        (fadeIn(animationSpec = tween(220)) +
                            slideInVertically(tween(280)) { it / 6 })
                            .togetherWith(fadeOut(tween(150)))
                    } else {
                        (fadeIn(animationSpec = tween(220)) +
                            slideInVertically(tween(280)) { it / 6 })
                            .togetherWith(fadeOut(tween(150)))
                    }
                },
                label = "page",
            ) { current ->
                when (current) {
                    AppPage.METER -> MeterPage(appViewModel)
                    AppPage.HISTORY -> HistoryPage(
                        appViewModel = appViewModel,
                        onBack = { appViewModel.navigateTo(AppPage.METER) },
                    )
                    AppPage.FILM -> FilmPage(
                        appViewModel = appViewModel,
                        onBack = { appViewModel.navigateTo(AppPage.METER) },
                    )
                    AppPage.TOOLS -> ToolsPage(
                        appViewModel = appViewModel,
                        onBack = { appViewModel.navigateTo(AppPage.METER) },
                    )
                    AppPage.SETTINGS -> SettingsPage(
                        appViewModel = appViewModel,
                        onBack = { appViewModel.navigateTo(AppPage.METER) },
                    )
                    AppPage.CALIBRATION -> CalibrationPage(
                        appViewModel = appViewModel,
                        onBack = { appViewModel.navigateTo(AppPage.SETTINGS) },
                    )
                }
            }
        }
    }
}
