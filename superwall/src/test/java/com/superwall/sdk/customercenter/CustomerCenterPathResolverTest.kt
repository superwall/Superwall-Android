package com.superwall.sdk.customercenter

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.customercenter.CustomerCenterConfiguration.Path
import com.superwall.sdk.customercenter.CustomerCenterFixtures.DAY_MS
import com.superwall.sdk.customercenter.CustomerCenterFixtures.customerInfo
import com.superwall.sdk.customercenter.CustomerCenterFixtures.entitlement
import com.superwall.sdk.customercenter.CustomerCenterFixtures.nonSubscription
import com.superwall.sdk.customercenter.CustomerCenterFixtures.now
import com.superwall.sdk.customercenter.CustomerCenterFixtures.presentation
import com.superwall.sdk.customercenter.CustomerCenterFixtures.subscription
import com.superwall.sdk.models.product.Store
import com.superwall.sdk.store.abstractions.product.receipt.LatestPeriodType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.Date

class CustomerCenterPathResolverTest {
    private val allPaths =
        listOf(
            Path.restore(),
            Path.changePlan(),
            Path.refund(),
            Path.manageSubscription(),
            Path.contactSupport(),
        )

    private fun resolve(
        purchase: PurchasePresentation?,
        paths: List<Path> = allPaths,
        product: ProductDisplayInfo? = null,
        supportEmailAvailable: Boolean = true,
        webManagementUrl: String? = null,
        isScreenLevel: Boolean = false,
        canOpenUrls: Boolean = true,
    ) = CustomerCenterPathResolver.resolve(
        paths,
        PathResolutionContext(
            purchase = purchase,
            product = product,
            supportEmailAvailable = supportEmailAvailable,
            webManagementUrl = webManagementUrl,
            canOpenUrls = canOpenUrls,
            isScreenLevel = isScreenLevel,
            now = now,
        ),
    )

    @Test
    fun `screen level without a purchase offers restore and support only`() =
        Given("no purchase") {
            val resolved = When("resolving the default paths") { resolve(null, isScreenLevel = true) }
            Then("only restore and contact support resolve") {
                assertEquals(listOf("restore", "contact_support"), resolved.map { it.id })
            }
        }

    @Test
    fun `an active renewing Play subscription gets every purchase action`() =
        Given("an active Play subscription") {
            val purchase = presentation(customerInfo(subscriptions = listOf(subscription())))
            val resolved = When("resolving for its detail screen") { resolve(purchase) }
            Then("change plan, refund, manage and support resolve, restore doesn't") {
                assertEquals(listOf("change_plan", "refund", "manage_subscription", "contact_support"), resolved.map { it.id })
                assertEquals(ResolvedPathDestination.PlayStoreManage("monthly"), resolved.first { it.id == "manage_subscription" }.destination)
                assertEquals(ResolvedPathDestination.Refund("monthly"), resolved.first { it.id == "refund" }.destination)
            }
        }

    @Test
    fun `restore stays available at screen level even with a purchase`() =
        Given("a purchase on a screen-level list") {
            val purchase = presentation(customerInfo(subscriptions = listOf(subscription())))
            val resolved = When("resolving at screen level") { resolve(purchase, listOf(Path.restore()), isScreenLevel = true) }
            Then("restore resolves") { assertEquals(listOf("restore"), resolved.map { it.id }) }
        }

    @Test
    fun `a cancelled Play subscription can't be cancelled again`() =
        Given("a Play subscription that won't renew") {
            val purchase = presentation(customerInfo(subscriptions = listOf(subscription(willRenew = false))))
            val resolved = When("resolving") { resolve(purchase, listOf(Path.manageSubscription())) }
            Then("manage doesn't resolve") { assertTrue(resolved.isEmpty()) }
        }

    @Test
    fun `a Play subscription without an expiration date can still be managed`() =
        Given("an active Play subscription the device reports no expiry for") {
            val purchase = presentation(customerInfo(subscriptions = listOf(subscription(expirationDate = null))))
            val resolved = When("resolving") { resolve(purchase, listOf(Path.manageSubscription())) }
            Then("manage resolves") { assertEquals(1, resolved.size) }
        }

    @Test
    fun `refund respects trials, free products and the window`() =
        Given("Play subscriptions in several states") {
            val trial = presentation(customerInfo(subscriptions = listOf(subscription(offerType = LatestPeriodType.TRIAL))))
            val old = presentation(customerInfo(subscriptions = listOf(subscription(purchaseDate = Date(now.time - 3 * DAY_MS)))))
            val paid = presentation(customerInfo(subscriptions = listOf(subscription())))
            Then("a trial, a free product and a purchase outside the window get no refund") {
                assertTrue(resolve(trial, listOf(Path.refund())).isEmpty())
                assertTrue(
                    resolve(paid, listOf(Path.refund()), product = ProductDisplayInfo("monthly", price = BigDecimal.ZERO)).isEmpty(),
                )
                assertTrue(resolve(old, listOf(Path.refund(windowMillis = 2 * DAY_MS))).isEmpty())
            }
            Then("a paid purchase inside the window does") {
                assertEquals(1, resolve(paid, listOf(Path.refund(windowMillis = 2 * DAY_MS))).size)
            }
        }

    @Test
    fun `web subscriptions go to the management page, or explain there isn't one`() =
        Given("an active Stripe subscription") {
            val purchase = presentation(customerInfo(subscriptions = listOf(subscription(store = Store.STRIPE))))
            Then("it goes to the configured page") {
                assertEquals(
                    ResolvedPathDestination.WebManage("https://x.superwall.app/manage"),
                    resolve(purchase, listOf(Path.manageSubscription()), webManagementUrl = "https://x.superwall.app/manage")
                        .single()
                        .destination,
                )
            }
            Then("without a page it explains where to find the link") {
                assertEquals(
                    ResolvedPathDestination.WebManageUnavailable,
                    resolve(purchase, listOf(Path.manageSubscription())).single().destination,
                )
            }
            Then("Play-only actions don't apply") {
                assertTrue(resolve(purchase, listOf(Path.refund(), Path.changePlan())).isEmpty())
            }
        }

    @Test
    fun `a lapsed or one-off web purchase has nothing to manage`() =
        Given("an expired Stripe subscription and a Stripe one-off") {
            val expired = presentation(customerInfo(subscriptions = listOf(subscription(store = Store.STRIPE, isActive = false))))
            val oneOff = presentation(customerInfo(nonSubscriptions = listOf(nonSubscription(store = Store.STRIPE))))
            Then("neither resolves a manage path") {
                assertTrue(resolve(expired, listOf(Path.manageSubscription()), webManagementUrl = "https://a.b").isEmpty())
                assertTrue(resolve(oneOff, listOf(Path.manageSubscription()), webManagementUrl = "https://a.b").isEmpty())
            }
        }

    @Test
    fun `a comped grant is only offered a page that exists`() =
        Given("an entitlement with no store behind it") {
            val purchase = presentation(customerInfo(entitlements = listOf(entitlement(store = null))))
            Then("without a page there's no row") { assertTrue(resolve(purchase, listOf(Path.manageSubscription())).isEmpty()) }
            Then("with a page it's offered") {
                assertEquals(1, resolve(purchase, listOf(Path.manageSubscription()), webManagementUrl = "https://a.b").size)
            }
        }

    @Test
    fun `a web entitlement without a transaction can still reach its page`() =
        Given("an entitlement that carries the Stripe store") {
            val purchase = presentation(customerInfo(entitlements = listOf(entitlement(store = Store.STRIPE))))
            val resolved = When("resolving without a page") { resolve(purchase, listOf(Path.manageSubscription())) }
            Then("it explains where the link is") {
                assertEquals(ResolvedPathDestination.WebManageUnavailable, resolved.single().destination)
            }
        }

    @Test
    fun `urls open in app only when they're web urls`() =
        Given("an https and a custom-scheme URL") {
            val paths =
                listOf(
                    Path.url("https://app.com/faq?token=1", title = "FAQ"),
                    Path.url("myapp://help", title = "Help"),
                    Path.url("https://app.com/terms", title = "Terms", openMethod = CustomerCenterConfiguration.OpenMethod.EXTERNAL),
                )
            val resolved = When("resolving") { resolve(null, paths) }
            Then("only the in-app https one opens in app") {
                assertEquals(listOf(true, false, false), resolved.map { (it.destination as ResolvedPathDestination.Url).inApp })
            }
            Then("ids leave out the query") { assertEquals("app.com/faq", resolved.first().id) }
            Then("nothing resolves when URLs can't be opened") { assertTrue(resolve(null, paths, canOpenUrls = false).isEmpty()) }
        }

    @Test
    fun `contact support needs an email`() =
        Given("no support email") {
            Then("the path doesn't resolve") {
                assertTrue(resolve(null, listOf(Path.contactSupport()), supportEmailAvailable = false).isEmpty())
            }
        }

    @Test
    fun `detail empty states say something true`() =
        Given("purchases with no actions") {
            val expired = presentation(customerInfo(subscriptions = listOf(subscription(isActive = false))))
            val appStore = presentation(customerInfo(subscriptions = listOf(subscription(store = Store.APP_STORE))))
            val custom = presentation(customerInfo(subscriptions = listOf(subscription(store = Store.CUSTOM))))
            Then("a lapsed subscription has nothing to do") {
                assertEquals(DetailEmptyState.NothingToDo, DetailEmptyStateResolver.resolve(expired, hasActions = false))
            }
            Then("an App Store subscription is managed through the App Store") {
                assertEquals(
                    DetailEmptyState.ManagedElsewhere("customer_center_store_app_store"),
                    DetailEmptyStateResolver.resolve(appStore, hasActions = false),
                )
            }
            Then("an unnamed store gets the generic sentence") {
                assertEquals(DetailEmptyState.ManagedElsewhere(null), DetailEmptyStateResolver.resolve(custom, hasActions = false))
            }
            Then("a screen with actions needs no sentence") {
                assertNull(DetailEmptyStateResolver.resolve(appStore, hasActions = true))
            }
        }
}
