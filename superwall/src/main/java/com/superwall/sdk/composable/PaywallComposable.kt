package com.superwall.sdk.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.superwall.sdk.Superwall
import com.superwall.sdk.paywall.presentation.get_paywall.getPaywall
import com.superwall.sdk.paywall.presentation.internal.request.PaywallOverrides
import com.superwall.sdk.paywall.vc.PaywallViewController
import com.superwall.sdk.paywall.vc.delegate.PaywallViewControllerDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun PaywallComposable(
    event: String,
    params: Map<String, Any>? = null,
    paywallOverrides: PaywallOverrides? = null,
    delegate: PaywallViewControllerDelegate,
    errorComposable: @Composable ((Throwable) -> Unit) = { error: Throwable ->
        // Default error composable
        Text(text = "No paywall to display")
    },
    loadingComposable: @Composable (() -> Unit) = {
        // Default loading composable
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }
        }
    }
) {
    val viewState = remember { mutableStateOf<PaywallViewController?>(null) }
    val errorState = remember { mutableStateOf<Throwable?>(null) }

    LaunchedEffect(Unit) {
        try {
            val newView = Superwall.instance.getPaywall(event, params, paywallOverrides, delegate)
            newView.viewWillAppear()
            viewState.value = newView
        } catch (e: Throwable) {
            errorState.value = e
        }
    }

    when {
        viewState.value != null -> {
            viewState.value?.let { viewToRender ->
                DisposableEffect(viewToRender) {
                    viewToRender.viewDidAppear()

                    onDispose {
                        viewToRender.viewWillDisappear()
                        CoroutineScope(Dispatchers.IO).launch {
                            viewToRender.viewDidDisappear()
                        }
                    }
                }
                AndroidView(
                    factory = { context ->
                        viewToRender
                    }
                )
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