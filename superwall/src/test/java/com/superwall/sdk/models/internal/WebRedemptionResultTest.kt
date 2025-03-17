package com.superwall.sdk.models.internal

import kotlinx.serialization.json.Json
import org.junit.Test

class WebRedemptionResultTest {
    @Test
    fun test_serialising_of_results() {
        val json =
            """
            {
              "codes": [
                {
                  "status": "SUCCESS",
                  "code": "…",
                  "redemptionInfo": {
                    "ownership": {
                      "type": "device",
                      "deviceId": "…"
                    },
                    "purchaserInfo": {
                      "appUserId": "…",
                      "email": "…",
                      "storeIdentifiers": {
                        "store": "STRIPE",
                        "stripeSubscriptionId": "some-id"
                      }
                    },
                    "paywallInfo": {
                      "identifier": "…",
                      "placementName": "…",
                      "placementParams": {},
                      "variantId": "…",
                      "experimentId": "…"
                    },
                    "entitlements": [
                      {
                        "identifier": "…",
                        "type": "SERVICE_LEVEL"
                      }
                    ]
                  }
                }
              ],
              "entitlements": [
                {
                  "identifier": "…",
                  "type": "SERVICE_LEVEL"
                }
              ]
            }

            """.trimIndent()

        val result = Json.decodeFromString<WebRedemptionResponse>(json)
        println(result)
    }
}
