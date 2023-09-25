package com.superwall.superapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import com.superwall.superapp.ui.theme.MyApplicationTheme

class ComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ComposeActivityContent()
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ComposeActivityContent() {
    val selectedTabIndex = remember { mutableStateOf(0) }

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
                        Text(text = "First Tab", modifier = Modifier.padding(16.dp))
                    }
                    Tab(
                        selected = selectedTabIndex.value == 1,
                        onClick = { selectedTabIndex.value = 1 }
                    ) {
                        Text(text = "Second Tab", modifier = Modifier.padding(16.dp))
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    when (selectedTabIndex.value) {
                        0 -> TabContent1()
                        1 -> TabContent2()
                    }
                }
            }
        }
    }
}

@Composable
fun TabContent1() {
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
fun TabContent2() {
    // Place your second tab content here
    Text("This is the second tab content.")
}