package com.mdblisthub.tv.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * Builds a ViewModel by calling its constructor.
 *
 * The graph is a field on the Application, so there is nothing for a DI
 * framework to resolve — this is the entire wiring layer the app needs.
 */
@Composable
inline fun <reified VM : ViewModel> hubViewModel(
    key: String? = null,
    crossinline create: () -> VM,
): VM = viewModel(key = key, factory = viewModelFactory { initializer { create() } })
