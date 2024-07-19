package com.superwall.sdk.paywall.presentation.rule_logic.javascript

import android.webkit.WebView
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.SandboxDeadException
import androidx.test.platform.app.InstrumentationRegistry
import com.superwall.sdk.models.triggers.TriggerRule
import com.superwall.sdk.storage.Storage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultJavascriptEvaluatorTest {
    fun ctx() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun evaulate_succesfully_with_sandbox() =
        runTest {
            val storage = mockk<Storage>()
            mockkStatic(WebView::class) {
                every { WebView.getCurrentWebViewPackage() } returns null
            }
            val evaulator =
                DefaultJavascriptEvalutor(
                    this,
                    CoroutineScope(Dispatchers.Main),
                    ctx(),
                    storage = storage,
                )
            evaulator.evaluate("console.assert(true);", TriggerRule.stub())
            evaulator.teardown()
        }

    @Test
    fun fail_evaluating_with_sandbox_and_fallback_is_used() =
        runTest {
            val storage = mockk<Storage>()

            val sandbox = JavaScriptSandbox.createConnectedInstanceAsync(ctx()).await()

            val mockSand =
                spyk(sandbox) {
                    every { createIsolate() } throws SandboxDeadException()
                }
            val evaulator =
                DefaultJavascriptEvalutor(
                    this,
                    CoroutineScope(Dispatchers.Main),
                    ctx(),
                    storage = storage,
                    createSandbox = {
                        sandbox
                    },
                )
            launch(Dispatchers.IO) {
                delay(100)
                evaulator.evaluate("console.assert(true);", TriggerRule.stub())
            }
            mockSand.killImmediatelyOnThread()
            evaulator.teardown()
        }
}
