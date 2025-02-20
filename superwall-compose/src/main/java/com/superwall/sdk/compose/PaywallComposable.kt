package com.superwall.sdk.compose

import android.app.Activity
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.superwall.sdk.paywall.presentation.get_paywall.builder.PaywallBuilder
import com.superwall.sdk.paywall.presentation.internal.request.PaywallOverrides
import com.superwall.sdk.paywall.view.PaywallView
import com.superwall.sdk.paywall.view.delegate.PaywallViewCallback
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
        Text(text = "No paywall to display")
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
    val viewState = remember { mutableStateOf<PaywallView?>(null) }
    val errorState = remember { mutableStateOf<Throwable?>(null) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        PaywallBuilder(placement)
            .params(params)
            .overrides(paywallOverrides)
            .delegate(delegate)
            .activity(context as Activity)
            .build()
            .fold(onSuccess = {
                viewState.value = it
            }, onFailure = {
                errorState.value = it
            })
    }

    when {
        viewState.value != null -> {
            viewState.value?.let { viewToRender ->
                DisposableEffect(viewToRender) {
                    viewToRender.onViewCreated()

                    onDispose {
                        viewToRender.beforeOnDestroy()
                        viewToRender.encapsulatingActivity = null

                        CoroutineScope(Dispatchers.Main).launch {
                            viewToRender.destroyed()
                        }
                    }
                }
                AndroidView(
                    modifier = modifier,
                    factory = { context ->
                        viewToRender
                    },
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
