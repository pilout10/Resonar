package com.resonar.app.ui

import androidx.compose.runtime.Composable

/** Entrada de la UI. El armado de pantallas vive en [AppShell]. */
@Composable
fun HomeScreen(viewModel: ResonarViewModel) {
    AppShell(viewModel)
}
