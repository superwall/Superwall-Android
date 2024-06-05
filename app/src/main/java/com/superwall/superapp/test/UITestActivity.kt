package com.superwall.superapp.test

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.superwall.superapp.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class UITestInfo(
    val number: Int,
    val description: String,
    val testCaseType: TestCaseType = TestCaseType.iOS,
)

enum class TestCaseType(
    val prefix: String,
) {
    iOS(prefix = "iOS"),
    Android("Android"),
    ;

    fun titleText(testCaseNumber: Int): String =
        when (this) {
            iOS -> "UITest $testCaseNumber"
            Android -> "$prefix UITest $testCaseNumber"
        }
}

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

    val mainTextColor =
        if (isSystemInDarkTheme()) {
            Color.White // Set the color for dark mode
        } else {
            Color.Black // Set the color for light mode
        }

    val tests =
        mapOf(
            UITestHandler.test0Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test0() } },
            UITestHandler.test1Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test1() } },
            UITestHandler.test2Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test2() } },
            UITestHandler.test3Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test3() } },
            UITestHandler.test4Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test4() } },
            UITestHandler.test5Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test5() } },
            UITestHandler.test6Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test6() } },
            UITestHandler.test7Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test7() } },
            UITestHandler.test8Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test8() } },
            UITestHandler.test9Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test9() } },
            UITestHandler.test10Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test10() } },
            UITestHandler.test11Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test11() } },
            UITestHandler.test12Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test12() } },
            UITestHandler.test13Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test13() } },
            UITestHandler.test14Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test14() } },
            UITestHandler.test15Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test15() } },
            UITestHandler.test16Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test16() } },
            UITestHandler.test17Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test17() } },
            UITestHandler.test18Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test18() } },
            UITestHandler.test19Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test19() } },
            UITestHandler.test20Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test20() } },
            UITestHandler.test21Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test21() } },
            UITestHandler.test22Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test22() } },
            UITestHandler.test23Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test23() } },
            UITestHandler.test24Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test24() } },
            UITestHandler.test25Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test25() } },
            UITestHandler.test26Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test26() } },
            UITestHandler.test27Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test27() } },
            UITestHandler.test28Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test28() } },
            UITestHandler.test29Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test29() } },
            UITestHandler.test30Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test30() } },
            UITestHandler.test31Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test31() } },
            UITestHandler.test32Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test32() } },
            UITestHandler.test33Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test33() } },
            UITestHandler.test34Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test34() } },
            UITestHandler.test35Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test35() } },
            UITestHandler.test36Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test36() } },
            UITestHandler.test37Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test37() } },
            UITestHandler.test41Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test41() } },
            UITestHandler.test42Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test42() } },
            UITestHandler.test43Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test43() } },
            UITestHandler.test44Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test44() } },
            UITestHandler.test45Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test45() } },
            UITestHandler.test46Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test46() } },
            UITestHandler.test47Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test47() } },
            UITestHandler.test48Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test48() } },
            UITestHandler.test50Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test50() } },
            UITestHandler.test52Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test52() } },
            UITestHandler.test53Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test53() } },
            UITestHandler.test56Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test56() } },
            UITestHandler.test57Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test57() } },
            UITestHandler.test58Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test58() } },
            UITestHandler.test59Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test59() } },
            UITestHandler.test60Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test60() } },
            UITestHandler.test62Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test62() } },
            UITestHandler.test63Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test63() } },
            UITestHandler.test64Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test64() } },
            UITestHandler.test65Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test65() } },
            UITestHandler.test66Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test66() } },
            UITestHandler.test68Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test68() } },
            UITestHandler.test70Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test70() } },
            UITestHandler.test71Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test71() } },
            UITestHandler.test72Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test72() } },
            UITestHandler.test74Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test74() } },
            UITestHandler.test75Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test75() } },
            UITestHandler.test82Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test82() } },
            UITestHandler.test83Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.test83() } },
            UITestHandler.testAndroid4Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.testAndroid4() } },
            UITestHandler.testAndroid9Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.testAndroid9() } },
            UITestHandler.testAndroid18Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.testAndroid18() } },
            UITestHandler.testAndroid19Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.testAndroid19() } },
            UITestHandler.testAndroid20Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.testAndroid20() } },
            UITestHandler.testAndroid21Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.testAndroid21() } },
            UITestHandler.testAndroid22Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.testAndroid22() } },
            UITestHandler.testAndroid23Info to { CoroutineScope(Dispatchers.IO).launch { UITestHandler.testAndroid23() } },
        )

    LazyColumn {
        items(tests.keys.toList()) { item ->
            Column {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier =
                            Modifier
                                .weight(2f)
                                .padding(horizontal = 8.dp),
                    ) {
                        Text(
                            color = mainTextColor,
                            text = item.testCaseType.titleText(item.number),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Text(
                            text = "${item.description}",
                            style = TextStyle(color = Color.Gray),
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                tests[item]?.invoke()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Launch")
                    }
                }
                Divider(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(0.5.dp),
                    color = Color.LightGray,
                )
            }
        }
    }
}
