package com.superwall.sdk.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.superwall.sdk.R
import com.superwall.sdk.paywall.presentation.get_paywall.builder.PaywallBuilder
import com.superwall.sdk.paywall.presentation.internal.request.PaywallOverrides
import com.superwall.sdk.paywall.view.PaywallView
import com.superwall.sdk.paywall.view.delegate.PaywallViewCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun PaywallComposable(
    modifier: Modifier = Modifier.fillMaxSize(),
    placement: String,
    params: Map<String, Any>? = null,
    paywallOverrides: PaywallOverrides? = null,
    delegate: PaywallViewCallback,
    errorComposable: @Composable ((Throwable) -> Unit) = { error: Throwable ->
        // Default error composable
        Text(text = stringResource(R.string.superwall_paywall_unavailable))
    },
    loadingComposable: @Composable (() -> Unit) = {
        // Default loading composable
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
        }
    },
) {
    // Keyed on the placement, so a new placement starts over from the loading state.
    val viewState = remember(placement) { mutableStateOf<PaywallView?>(null) }
    val errorState = remember(placement) { mutableStateOf<Throwable?>(null) }
    val localContext = LocalContext.current
    val activity = remember(localContext) { localContext.findNearestActivityOrNull() }

    @Composable
    fun rememberThemeChanged(): Boolean {
        val isDark = isSystemInDarkTheme()

        var lastTheme by rememberSaveable { mutableStateOf(isDark) }
        var hasChanged by remember { mutableStateOf(false) }

        LaunchedEffect(isDark) {
            if (isDark != lastTheme) {
                hasChanged = true
                lastTheme = isDark
            } else {
                hasChanged = false
            }
        }

        return hasChanged
    }
    LaunchedEffect(placement) {
        if (activity == null) {
            errorState.value = IllegalStateException("No Activity attached - activity required")
            return@LaunchedEffect
        }
        try {
            PaywallBuilder(placement)
                .params(params)
                .overrides(paywallOverrides)
                .delegate(delegate)
                .activity(activity)
                .build()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // e.g. Superwall hasn't been configured yet
            Result.failure(e)
        }.fold(onSuccess = {
            viewState.value = it
        }, onFailure = {
            errorState.value = it
        })
    }

    when {
        viewState.value != null -> {
            viewState.value?.let { viewToRender ->
                LaunchedEffect(viewToRender) {
                    viewToRender.onViewCreated()
                }
                val themeChanged = rememberThemeChanged()
                // A new view (e.g. for a new placement) replaces the old one, which is released.
                key(viewToRender) {
                    AndroidView(
                        modifier = modifier,
                        update = {
                            if (themeChanged && it.state.isPresented) {
                                it.onThemeChanged()
                            }
                        },
                        factory = { context ->
                            viewToRender
                        },
                        onRelease = {
                            viewToRender.beforeOnDestroy(forceCleanup = true)
                            viewToRender.encapsulatingActivity = null

                            CoroutineScope(Dispatchers.Main).launch {
                                viewToRender.destroyed(forceCleanup = true)
                                viewToRender.cleanup()
                            }
                        },
                    )
                }
            }
        }

        errorState.value != null -> {
            errorComposable(errorState.value!!)
        }

        else -> {
            loadingComposable()
        }
    }
}

fun Context.findNearestActivity(): Activity =
    findNearestActivityOrNull() ?: throw IllegalStateException("No Activity attached - activity required")

internal fun Context.findNearestActivityOrNull(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext?.findNearestActivityOrNull()
        else -> null
    }
