package com.superwall.superapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.superwall.superapp.ui.theme.MyApplicationTheme
import android.view.View
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import com.superwall.sdk.paywall.presentation.internal.request.PaywallOverrides
import com.superwall.sdk.paywall.vc.delegate.PaywallViewControllerDelegate
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.superwall.sdk.Superwall
import com.superwall.sdk.paywall.presentation.get_paywall.getPaywall
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.vc.PaywallViewController

class ComposeActivity : ComponentActivity(), PaywallViewControllerDelegate {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ComposeActivityContent(this)
        }
    }

    override fun didFinish(
        paywall: PaywallViewController,
        result: PaywallResult,
        shouldDismiss: Boolean
    ) {
        // TODO: Add an implementation here if the paywall view controller would dismiss.
        // In the context of a tab bar, there won't be a dismissal.
    }
}

@Preview(showBackground = true)
@Composable
fun ComposeActivityContent(@PreviewParameter(PreviewPaywallDelegateProvider::class) delegate: PaywallViewControllerDelegate) {
    val selectedTabIndex = remember { mutableStateOf(0) }
    val examplePaywallOverrides = PaywallOverrides()

    MyApplicationTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column {
                TabRow(selectedTabIndex = selectedTabIndex.value) {
                    Tab(
                        selected = selectedTabIndex.value == 0,
                        onClick = { selectedTabIndex.value = 0 }
                    ) {
                        Text(text = "Tab 0", modifier = Modifier.padding(16.dp))
                    }
                    Tab(
                        selected = selectedTabIndex.value == 1,
                        onClick = { selectedTabIndex.value = 1 }
                    ) {
                        Text(text = "Tab 1", modifier = Modifier.padding(16.dp))
                    }
                    Tab(
                        selected = selectedTabIndex.value == 2,
                        onClick = { selectedTabIndex.value = 2 }
                    ) {
                        Text(text = "Tab 2", modifier = Modifier.padding(16.dp))
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    when (selectedTabIndex.value) {
                        0 -> TabContent0(examplePaywallOverrides, delegate = delegate)
                        1 -> TabContent1(examplePaywallOverrides, delegate = delegate)
                        2 -> TabContent2()
                    }
                }
            }
        }
    }
}

@Composable
fun TabContent0(paywallOverrides: PaywallOverrides?, delegate: PaywallViewControllerDelegate) {
    PaywallComposable(
        event = "another_paywall",
        params = mapOf("key" to "value"),
        paywallOverrides = paywallOverrides,
        delegate = delegate
    )
}

@Composable
fun TabContent1(paywallOverrides: PaywallOverrides?, delegate: PaywallViewControllerDelegate) {
    PaywallComposable(
        event = "no-existing-event",
        params = mapOf("key" to "value"),
        paywallOverrides = paywallOverrides,
        delegate = delegate
    )
}

@Composable
fun TabContent2() {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Greeting("Jetpack Compose")
            EventButton()
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Composable
fun EventButton() {
    val context = LocalContext.current

    Button(onClick = {
        val app = context.applicationContext as? MainApplication
        app?.invokeRegister("another_paywall")
    }, modifier = Modifier
        .width(250.dp)
    ){
        Text("Another Paywall")
    }
}

@Composable
fun PaywallComposable(
    event: String,
    params: Map<String, Any>? = null,
    paywallOverrides: PaywallOverrides? = null,
    delegate: PaywallViewControllerDelegate
) {
    val viewState = remember { mutableStateOf<PaywallViewController?>(null) }
    val errorState = remember { mutableStateOf<Throwable?>(null) }

    LaunchedEffect(Unit) {
        try {
            val newView = Superwall.instance.getPaywall(event, params, paywallOverrides, delegate)
            viewState.value = newView
        } catch (e: Throwable) {
            errorState.value = e
        }
    }

    when {
        viewState.value != null -> {
            // If a paywall is returned, it'll be provided here
            viewState.value?.let { viewToRender ->
                AndroidView(
                    factory = { context ->
                        viewToRender
                    }
                )
            }
        }
        errorState.value != null -> {
            // Some other composable to display if no paywall is returned
            Text(text = "No paywall to display")
        }
        else -> {
            // Some other composable to display while waiting for a potential paywall
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
    }
}

// Mock Provider for Preview
class PreviewPaywallDelegateProvider : PreviewParameterProvider<PaywallViewControllerDelegate> {
    override val values: Sequence<PaywallViewControllerDelegate> = sequenceOf(
        object : PaywallViewControllerDelegate {
            // Mock implementation of PaywallViewControllerDelegate
            override fun didFinish(
                paywall: PaywallViewController,
                result: PaywallResult,
                shouldDismiss: Boolean
            ) {
                // No implementation required
            }
        }
    )
}