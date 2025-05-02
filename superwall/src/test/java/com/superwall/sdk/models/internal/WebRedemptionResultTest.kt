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
      "code": "redemption_c6358802-ab07-4642-a361-d7352473505a",
      "redemptionInfo": {
        "ownership": {
          "type": "app_user",
          "appUserId": "Alias:d1ba57dc-15bb-46e3-864c-a1f8805fe746"
        },
        "purchaserInfo": {
          "appUserId": "Alias:d1ba57dc-15bb-46e3-864c-a1f8805fe746",
          "email": "ian@superwall.com",
          "storeIdentifiers": {
            "store": "STRIPE",
            "stripeSubscriptionId": "sub_1R3bMoBitwqMmwU0Wm4OgMsj"
          }
        },
        "paywallInfo": null,
        "entitlements": [
          {
            "identifier": "pro",
            "type": "SERVICE_LEVEL"
          }
        ]
      }
    }
  ],
  "entitlements": [
    {
      "identifier": "pro",
      "type": "SERVICE_LEVEL"
    }
  ]
}
            """.trimIndent()

        val result = Json {}.decodeFromString<WebRedemptionResponse>(json)
        assert(result != null)
    }

    @Test
    fun test_code_expired() {
        val json = """{
  "codes": [
    {
      "status": "CODE_EXPIRED",
      "code": "redemption_296db3d9-0f38-4399-a809-25b50849c753",
      "expired": {
        "resent": false,
        "obfuscatedEmail": null
      }
    }
  ],
  "entitlements": []
}"""

        val result = Json {}.decodeFromString<WebRedemptionResponse>(json)
        assert(result != null)
    }
}
