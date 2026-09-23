package com.superwall.sdk.customercenter

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.customercenter.CustomerCenterFixtures.DAY_MS
import com.superwall.sdk.customercenter.CustomerCenterFixtures.customerInfo
import com.superwall.sdk.customercenter.CustomerCenterFixtures.entitlement
import com.superwall.sdk.customercenter.CustomerCenterFixtures.nonSubscription
import com.superwall.sdk.customercenter.CustomerCenterFixtures.now
import com.superwall.sdk.customercenter.CustomerCenterFixtures.subscription
import com.superwall.sdk.models.product.Store
import com.superwall.sdk.store.abstractions.product.receipt.LatestPeriodType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class PurchasePresentationBuilderTest {
    private val dateFormat =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val builder = PurchasePresentationBuilder(CustomerCenterStrings.english, Locale.US, dateFormat)

    @Test
    fun `badges follow the subscription's state`() =
        Given("subscriptions in each state") {
            Then("each gets the right badge") {
                assertEquals(PurchaseBadge.REVOKED, builder.badge(subscription(isRevoked = true)))
                assertEquals(PurchaseBadge.EXPIRED, builder.badge(subscription(isActive = false)))
                assertEquals(PurchaseBadge.BILLING_ISSUE, builder.badge(subscription(isInGracePeriod = true)))
                assertEquals(PurchaseBadge.BILLING_ISSUE, builder.badge(subscription(isInBillingRetryPeriod = true)))
                assertEquals(PurchaseBadge.CANCELLED, builder.badge(subscription(willRenew = false)))
                assertEquals(PurchaseBadge.FREE_TRIAL, builder.badge(subscription(offerType = LatestPeriodType.TRIAL)))
                assertEquals(PurchaseBadge.ACTIVE, builder.badge(subscription()))
            }
        }

    @Test
    fun `renewals of one subscription collapse into a single row`() =
        Given("three transactions of the same product") {
            val old = subscription(isActive = false, expirationDate = Date(now.time - 30 * DAY_MS), transactionId = "a")
            val current = subscription(transactionId = "b")
            val older = subscription(isActive = false, expirationDate = Date(now.time - 60 * DAY_MS), transactionId = "c")
            val rows = When("building") { builder.build(customerInfo(subscriptions = listOf(old, current, older)), emptyMap()) }
            Then("there's one row, for the active transaction") {
                assertEquals(1, rows.size)
                assertEquals("b", rows.single().subscription?.transactionId)
            }
        }

    @Test
    fun `active subscriptions come first`() =
        Given("an expired and an active subscription") {
            val rows =
                When("building") {
                    builder.build(
                        customerInfo(subscriptions = listOf(subscription("old", isActive = false), subscription("new"))),
                        emptyMap(),
                    )
                }
            Then("the active one is first") { assertEquals(listOf("new", "old"), rows.map { it.productId }) }
        }

    @Test
    fun `status line quotes the renewal date and price`() =
        Given("an active subscription with product info") {
            val product = ProductDisplayInfo("monthly", title = "Pro Monthly", localizedPrice = "$4.99", localizedPeriod = "month")
            val row =
                When("building") {
                    builder.build(customerInfo(subscriptions = listOf(subscription())), mapOf("monthly" to product)).single()
                }
            Then("title, price and status read correctly") {
                assertEquals("Pro Monthly", row.title)
                assertEquals("$4.99 / month", row.priceLine)
                assertEquals("Renews on ${dateFormat.format(Date(now.time + 29 * DAY_MS))} for $4.99", row.statusLine)
                assertNull(row.storeLabelKey)
            }
        }

    @Test
    fun `a product without a name is headed by its entitlement, never its id`() =
        Given("a web subscription whose product has no name") {
            val info =
                customerInfo(
                    subscriptions = listOf(subscription("price_123", store = Store.STRIPE)),
                    entitlements = listOf(entitlement("pro", productIds = setOf("price_123")), entitlement("basic", productIds = setOf("price_123"))),
                )
            val row = When("building") { builder.build(info, emptyMap()).single() }
            Then("the lowest entitlement id heads the card") {
                assertEquals("basic", row.title)
                assertEquals("customer_center_store_web", row.storeLabelKey)
                assertEquals(2, row.entitlements.size)
            }
        }

    @Test
    fun `a product with no name and no entitlement has no title`() =
        Given("a subscription with nothing to name it by") {
            val row = When("building") { builder.build(customerInfo(subscriptions = listOf(subscription("x"))), emptyMap()).single() }
            Then("the title is absent") { assertNull(row.title) }
        }

    @Test
    fun `entitlements without a transaction still show`() =
        Given("an active entitlement for a product with no transaction, and an inactive one") {
            val info =
                customerInfo(
                    entitlements =
                        listOf(
                            entitlement("pro", productIds = setOf("web_pro"), store = Store.STRIPE, latestProductId = "web_pro"),
                            entitlement("old", isActive = false),
                        ),
                )
            val rows = When("building") { builder.build(info, emptyMap()) }
            Then("only the active one shows, as an entitlement-only purchase") {
                assertEquals(1, rows.size)
                assertTrue(rows.single().kind is PurchaseKind.EntitlementOnly)
                assertEquals("web_pro", rows.single().productId)
                assertEquals("Active", rows.single().statusLine)
                assertTrue(rows.single().opensDetail)
            }
        }

    @Test
    fun `an entitlement covered by a transaction isn't shown twice`() =
        Given("an entitlement unlocked by a subscription that is listed") {
            val info =
                customerInfo(
                    subscriptions = listOf(subscription()),
                    entitlements = listOf(entitlement("pro", productIds = setOf("monthly"))),
                )
            val rows = When("building") { builder.build(info, emptyMap()) }
            Then("there is one row") { assertEquals(1, rows.size) }
        }

    @Test
    fun `one-off purchases each get a row and don't open a detail screen`() =
        Given("two purchases of the same consumable") {
            val info =
                customerInfo(
                    nonSubscriptions =
                        listOf(
                            nonSubscription(transactionId = "1"),
                            nonSubscription(transactionId = "2", isRevoked = true),
                        ),
                )
            val rows = When("building") { builder.build(info, emptyMap()) }
            Then("each is its own row") {
                assertEquals(listOf("1", "2"), rows.map { it.id })
                assertFalse(rows.first().opensDetail)
                assertEquals(PurchaseBadge.REVOKED, rows.last().badge)
                assertEquals("Refunded", rows.last().statusLine)
            }
        }

    @Test
    fun `products awaiting the catalogue are flagged`() =
        Given("a web subscription whose details are loading") {
            val info = customerInfo(subscriptions = listOf(subscription("web", store = Store.PADDLE)))
            val rows = When("building") { builder.build(info, emptyMap(), awaitingCatalogue = setOf("web")) }
            Then("the row shows placeholders") { assertTrue(rows.single().isAwaitingCatalogue) }
        }
}
