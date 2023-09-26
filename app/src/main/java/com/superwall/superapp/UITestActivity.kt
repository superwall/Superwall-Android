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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.superwall.superapp.ui.theme.MyApplicationTheme
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

data class UITestInfo(val number: Int, val description: String)

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
    val uiTestHandler = UITestHandler()
    val scope = rememberCoroutineScope()
    UITestHandler.context = LocalContext.current

    val tests = mapOf(
        UITestHandler.test0Info to { scope.launch { UITestHandler.test0() } },
        UITestHandler.test1Info to { scope.launch { UITestHandler.test1() } },
        UITestHandler.test2Info to { scope.launch { UITestHandler.test2() } },
        UITestHandler.test3Info to { scope.launch { UITestHandler.test3() } },
        UITestHandler.test4Info to { scope.launch { UITestHandler.test4() } },
        UITestHandler.test5Info to { scope.launch { UITestHandler.test5() } },
        UITestHandler.test6Info to { scope.launch { UITestHandler.test6() } },
        UITestHandler.test7Info to { scope.launch { UITestHandler.test7() } },
        UITestHandler.test8Info to { scope.launch { UITestHandler.test8() } },
        UITestHandler.test9Info to { scope.launch { UITestHandler.test9() } },
        UITestHandler.test10Info to { scope.launch { UITestHandler.test10() } },
        UITestHandler.test11Info to { scope.launch { UITestHandler.test11() } },
        UITestHandler.test12Info to { scope.launch { UITestHandler.test12() } },
        UITestHandler.test13Info to { scope.launch { UITestHandler.test13() } },
        UITestHandler.test14Info to { scope.launch { UITestHandler.test14() } },
        UITestHandler.test15Info to { scope.launch { UITestHandler.test15() } },
        UITestHandler.test16Info to { scope.launch { UITestHandler.test16() } },
        UITestHandler.test17Info to { scope.launch { UITestHandler.test17() } },
        UITestHandler.test18Info to { scope.launch { UITestHandler.test18() } },
        UITestHandler.test19Info to { scope.launch { UITestHandler.test19() } },
        UITestHandler.test20Info to { scope.launch { UITestHandler.test20() } },
        UITestHandler.test21Info to { scope.launch { UITestHandler.test21() } },
        UITestHandler.test22Info to { scope.launch { UITestHandler.test22() } },
        UITestHandler.test23Info to { scope.launch { UITestHandler.test23() } },
        UITestHandler.test24Info to { scope.launch { UITestHandler.test24() } },
        UITestHandler.test25Info to { scope.launch { UITestHandler.test25() } },
        UITestHandler.test28Info to { scope.launch { UITestHandler.test28() } },
        UITestHandler.test29Info to { scope.launch { UITestHandler.test29() } },
        UITestHandler.test30Info to { scope.launch { UITestHandler.test30() } },
        UITestHandler.test31Info to { scope.launch { UITestHandler.test31() } },
        UITestHandler.test33Info to { scope.launch { UITestHandler.test33() } },
        UITestHandler.test34Info to { scope.launch { UITestHandler.test34() } },
        UITestHandler.test62Info to { scope.launch { UITestHandler.test62() } },
        UITestHandler.test72Info to { scope.launch { UITestHandler.test72() } }
    )

    LazyColumn {
        items(tests.keys.toList()) { item ->
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(2f)
                            .padding(horizontal = 8.dp)
                    ) {
                        Text(
                            color = Color.Black,
                            text = "UITest ${item.number}",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            text = "${item.description}",
                            style = TextStyle(color = Color.Gray)
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                tests[item]?.invoke()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Launch")
                    }
                }
                Divider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp),
                    color = Color.LightGray
                )
            }
        }
    }
}