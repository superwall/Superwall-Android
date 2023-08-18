package com.superwall.sdk.billing


import android.content.Context
import android.util.Log
import com.android.billingclient.api.*

class BillingController(context: Context) : PurchasesUpdatedListener {

    private lateinit var billingClient: BillingClient

    init {
        billingClient = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases()
            .build()
        startConnection() {
            if (it) {
                Log.d("BillingController", "Billing client connected")
            } else {
                Log.d("BillingController", "Billing client failed to connect")
            }
        }
    }


    override fun onPurchasesUpdated(p0: BillingResult, p1: MutableList<Purchase>?) {
//        TODO("Not yet implemented")
        // Not really sure what this does...
    }

    private fun startConnection(completion: (success: Boolean) -> Unit) {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                Log.d(
                    "BillingController",
                    "Billing client setup finished".plus(billingResult.responseCode)
                )
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    // The billing client is ready. You can query purchases here.
//                    querySkuDetails("your_product_id")
                    completion(true)
                } else {
                    completion(false)
                }
            }

            override fun onBillingServiceDisconnected() {
                // Try to restart the connection if it was lost.
                startConnection() {
                    completion(it)
                }
            }
        })
    }


    private fun connected(): Boolean {
        return billingClient.isReady
    }


    public fun querySkuDetails(
        productIds: ArrayList<String>,
        callback: (skuDetails: ArrayList<SkuDetails>) -> Unit
    ) {
        if (connected()) {
            _querySkuDetails(productIds, callback)
        } else {
            startConnection { success ->
                if (success) {
                    _querySkuDetails(productIds, callback)
                } else {
                    Log.d("SkuDetails", "Failed to connect to billing client")
                    callback(arrayListOf())
                }
            }
        }
    }


    private fun _querySkuDetails(
        productIds: ArrayList<String>,
        callback: (skuDetails: ArrayList<SkuDetails>) -> Unit
    ) {
        // TODO: Make sure billingClient is connected before calling querySkuDetailsAsync

        Log.d("SkuDetails", "Querying SkuDetails: ".plus(productIds.toString()))
        val skuList = productIds
        val params = SkuDetailsParams.newBuilder()
        params.setSkusList(skuList).setType(BillingClient.SkuType.SUBS)

        billingClient.querySkuDetailsAsync(params.build(),
            object : SkuDetailsResponseListener {
                override fun onSkuDetailsResponse(
                    billingResult: BillingResult,
                    skuDetailsList: List<SkuDetails>?
                ) {
                    Log.d(
                        "SkuDetails",
                        "Got SkuDetails".plus(skuDetailsList.toString())
                            .plus(billingResult.responseCode)
                    )
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && skuDetailsList != null) {
                        callback(skuDetailsList as ArrayList<SkuDetails>)
                        for (skuDetails in skuDetailsList) {


                            // Display SKU details
                            Log.d("SkuDetails", "Product ID: ${skuDetails.sku}")
                            Log.d("SkuDetails", "Title: ${skuDetails.title}")
                            Log.d("SkuDetails", "Description: ${skuDetails.description}")
                            Log.d("SkuDetails", "Price: ${skuDetails.price}")
                        }
                    }
                }
            })
    }

}



