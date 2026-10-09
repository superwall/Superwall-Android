package com.superwall.sdk.customercenter

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.customercenter.CustomerCenterConfiguration.Appearance
import com.superwall.sdk.customercenter.CustomerCenterConfiguration.FeedbackSurvey
import com.superwall.sdk.customercenter.CustomerCenterConfiguration.Path
import com.superwall.sdk.customercenter.CustomerCenterConfiguration.Screen
import com.superwall.sdk.customercenter.CustomerCenterConfiguration.Support
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerCenterConfigurationTest {
    @Test
    fun `the default configuration matches iOS`() =
        Given("the default configuration") {
            val config = CustomerCenterConfiguration.default
            Then("the management screen offers the five built-in paths in order") {
                assertEquals(
                    listOf("restore", "change_plan", "refund", "manage_subscription", "contact_support"),
                    config.managementScreen.paths.map { it.id },
                )
                assertEquals(FeedbackSurvey.cancellation, config.managementScreen.paths[3].survey)
            }
            Then("the no-purchases screen offers restore") {
                assertEquals(listOf("restore"), config.noPurchasesScreen.paths.map { it.id })
            }
            Then("it's a fresh copy each time") { assertTrue(config == CustomerCenterConfiguration.default) }
        }

    @Test
    fun `default ids come from the path type`() =
        Given("paths of each type") {
            Then("each gets its default id") {
                assertEquals("refund", Path.refund(windowMillis = 1).id)
                assertEquals("app.com/faq", Path.url("https://user:pw@app.com/faq?token=secret#top", title = "FAQ").id)
                assertEquals("my_action", Path.custom("my_action").id)
                assertEquals("refund_7d", Path.refund(id = "refund_7d").id)
            }
        }

    @Test
    fun `configuration problems are found`() =
        Given("a configuration with every mistake") {
            val config =
                CustomerCenterConfiguration(
                    managementScreen =
                        Screen(
                            paths =
                                listOf(
                                    Path.refund(),
                                    Path.refund(windowMillis = 1),
                                    Path(CustomerCenterConfiguration.PathType.Url("https://a.com/x")),
                                    Path.restore(survey = FeedbackSurvey("s", options = listOf(FeedbackSurvey.Option.dontUse))),
                                    Path.manageSubscription(survey = FeedbackSurvey.cancellation),
                                ),
                        ),
                    noPurchasesScreen = Screen(paths = listOf(Path.restore())),
                    appearance = Appearance(Appearance.ColorPair(light = "#FF0000", dark = "blue")),
                )
            Then("each is reported") {
                assertEquals(listOf("refund"), config.duplicatePathIds)
                assertEquals(listOf("a.com/x"), config.untitledUrlPathIds)
                assertEquals(listOf("restore"), config.untitledSurveyPathIds)
                assertEquals(listOf("blue"), config.invalidAccentHexes)
            }
            Then("the default configuration has none") {
                val default = CustomerCenterConfiguration.default
                assertTrue(default.duplicatePathIds.isEmpty())
                assertTrue(default.untitledSurveyPathIds.isEmpty())
            }
        }

    @Test
    fun `hex colours parse as RRGGBB and RRGGBBAA`() =
        Given("hex strings") {
            Then("they become ARGB ints") {
                assertEquals(0xFFFF0000.toInt(), CustomerCenterColors.parseHex("#FF0000"))
                assertEquals(0x80112233.toInt(), CustomerCenterColors.parseHex("#11223380"))
                assertEquals(0xFF00FF00.toInt(), CustomerCenterColors.parseHex(" 00FF00 "))
                assertNull(CustomerCenterColors.parseHex("#FFF"))
                assertNull(CustomerCenterColors.parseHex("#GGGGGG"))
            }
        }

    @Test
    fun `urls are reported without anything that could carry a token`() =
        Given("a url with user info, query and fragment") {
            val url = "https://user:pw@app.com/faq?token=secret#top"
            Then("only scheme, host and path are kept") {
                assertEquals("https://app.com/faq", CustomerCenterUrls.withoutQueryOrFragment(url))
                assertEquals("app.com", CustomerCenterUrls.host(url))
            }
        }

    @Test
    fun `actions map from path types`() =
        Given("each path type") {
            Then("each maps to its action and analytics name") {
                assertEquals(CustomerCenterAction.Restore, CustomerCenterAction.from(CustomerCenterConfiguration.PathType.Restore))
                assertEquals("change_plan", CustomerCenterAction.from(CustomerCenterConfiguration.PathType.ChangePlan()).analyticsName)
                assertEquals(
                    CustomerCenterAction.Url("https://a.com"),
                    CustomerCenterAction.from(CustomerCenterConfiguration.PathType.Url("https://a.com")),
                )
                assertEquals("custom", CustomerCenterAction.Custom("x").analyticsName)
                assertEquals("user_cancelled", CustomerCenterRefundStatus.USER_CANCELLED.analyticsName)
                assertEquals("no_purchases", CustomerCenterScreenType.NO_PURCHASES.analyticsName)
            }
        }

    @Test
    fun `support email carries diagnostics`() =
        Given("a support email") {
            val diagnostics =
                SupportEmailDiagnostics("user 1", "1.0", "14", "Pixel", "2.9.0", listOf("pro", "gold"), isSandbox = true)
            val url =
                When("composing") {
                    SupportEmailComposer.mailtoUrl(" help@app.com ", "Support request", "Hi & bye", diagnostics)!!
                }
            Then("it's a mailto with encoded subject and body") {
                assertTrue(url.startsWith("mailto:help@app.com?subject=Support%20request&body="))
                val body = java.net.URLDecoder.decode(url.substringAfter("body="), "UTF-8")
                assertTrue(body.startsWith("Hi & bye\n\n---"))
                assertTrue(body.contains("- User ID: user 1"))
                assertTrue(body.contains("- Entitlements: pro, gold"))
                assertTrue(body.contains("- Sandbox: true"))
            }
            Then("no email means no url") {
                assertNull(SupportEmailComposer.mailtoUrl("  ", "s", "b", diagnostics))
                assertNull(SupportEmailComposer.mailtoUrl(null, "s", "b", diagnostics))
            }
        }

    @Test
    fun `versions compare only when installed is older`() =
        Given("version strings") {
            Then("only a strictly older, parseable install is older") {
                assertTrue(AppVersionComparator.isInstalledVersionOlder("1.2.3", "1.2.4"))
                assertTrue(AppVersionComparator.isInstalledVersionOlder("1.9", "1.10"))
                assertTrue(AppVersionComparator.isInstalledVersionOlder("1", "1.0.1"))
                assertFalse(AppVersionComparator.isInstalledVersionOlder("1.2.3", "1.2.3"))
                assertFalse(AppVersionComparator.isInstalledVersionOlder("2.0", "1.9.9"))
                assertFalse(AppVersionComparator.isInstalledVersionOlder("beta", "1.0"))
                assertFalse(AppVersionComparator.isInstalledVersionOlder("1.0", null))
                assertEquals(listOf(1, 2, 0), AppVersionComparator.parse("1.2.x"))
            }
        }

    @Test
    fun `the web management page is derived from the restore url`() =
        Given("restore urls") {
            Then("a superwall.app url becomes its manage page") {
                assertEquals(
                    "https://myapp.superwall.app/manage",
                    WebManagementUrlResolver.resolve(null, "https://myapp.superwall.app/restore?code=1"),
                )
            }
            Then("other hosts are used as they are") {
                assertEquals("https://example.com/r", WebManagementUrlResolver.resolve(null, "https://example.com/r"))
            }
            Then("an override wins, and nothing gives nothing") {
                assertEquals("https://o.com", WebManagementUrlResolver.resolve("https://o.com", "https://a.superwall.app/r"))
                assertNull(WebManagementUrlResolver.resolve(null, ""))
            }
        }

    @Test
    fun `support defaults warn about updates`() =
        Given("default support settings") {
            val support = Support()
            Then("updates are warned about and nothing else is set") {
                assertTrue(support.warnsAboutUpdates)
                assertNull(support.email)
                assertNull(support.latestAppVersion)
            }
        }
}
