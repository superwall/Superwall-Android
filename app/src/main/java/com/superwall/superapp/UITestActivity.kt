package com.superwall.superapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.superwall.superapp.ui.theme.MyApplicationTheme
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class UITestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyApplicationTheme {
                UITestTable()
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun UITestTable() {
    val context = LocalContext.current
    val uiTestHandler = UITestHandler()
    val scope = rememberCoroutineScope()

    val tests = mapOf(
        0 to uiTestHandler::test0,
        1 to uiTestHandler::test1,
        2 to uiTestHandler::test2,
        3 to uiTestHandler::test3,
        4 to uiTestHandler::test4,
        5 to uiTestHandler::test5,
        6 to uiTestHandler::test6,
        7 to uiTestHandler::test7,
        8 to uiTestHandler::test8,
        9 to uiTestHandler::test9,
        10 to uiTestHandler::test10,
        11 to uiTestHandler::test11,
        12 to uiTestHandler::test12,
        13 to uiTestHandler::test13,
        14 to uiTestHandler::test14,
        15 to uiTestHandler::test15,
        16 to uiTestHandler::test16,
        17 to uiTestHandler::test17,
        18 to uiTestHandler::test18,
        19 to uiTestHandler::test19,
        20 to uiTestHandler::test20,
        21 to uiTestHandler::test21,
        22 to uiTestHandler::test22,
        23 to uiTestHandler::test23,
        24 to uiTestHandler::test24,
        33 to uiTestHandler::test33,
        34 to uiTestHandler::test34
    )

    LazyColumn {
        items(tests.keys.toList()) { item ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "UITest $item",
                    modifier = Modifier.padding(16.dp)
                )
                Button(
                    onClick = {
                        scope.launch {
                            tests[item]?.invoke()
                        }
                    }
                ) {
                    Text("Launch")
                }
            }
        }
    }
}