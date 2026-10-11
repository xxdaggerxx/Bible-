package com.biblestudy.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

actual val fastInkSupported: Boolean = false

@Composable
actual fun FastInkLayer(modifier: Modifier, onChange: (FastInk?) -> Unit) = Unit
