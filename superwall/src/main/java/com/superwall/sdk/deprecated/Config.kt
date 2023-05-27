//package com.superwall.sdk.api
//
//import FeatureFlags
//import Paywall
//import Paywalls
//import PreloadingDisabled
//import Trigger
//import org.json.JSONArray
//import org.json.JSONObject
//
//data class Config(
//    val triggers: Set<Trigger>,
//    val paywalls: List<Paywall>,
//    val logLevel: Int,
////    val postback: PostbackRequest,
//    val appSessionTimeout: Long,
//    val featureFlags: FeatureFlags,
//    val preloadingDisabled: PreloadingDisabled,
//    val locales: Set<String>,
//    val requestId: String? = null
//) {
//    companion object {
//        fun fromJson(json: JSONObject): Config {
//            val localizationConfig = json.getJSONObject("localization")
//            val locales = mutableSetOf<String>()
//            val localesJsonArray = localizationConfig.getJSONArray("locales")
//
//            for (i in 0 until localesJsonArray.length()) {
//                locales.add(localesJsonArray.getJSONObject(i).getString("locale"))
//            }
//
//            return Config(
//                triggers = parseTriggers(json.getJSONArray("trigger_options")),
//                paywalls = parsePaywalls(json.getJSONArray("paywall_responses")),
//                logLevel = json.getInt("log_level"),
////                postback = parsePostbackRequest(json.getJSONObject("postback")),
//                appSessionTimeout = json.getLong("app_session_timeout_ms"),
//                featureFlags = parseFeatureFlags(json.getJSONArray("toggles")),
//                preloadingDisabled = parsePreloadingDisabled(json.getJSONObject("disable_preload")),
//                locales = locales
//            )
//        }
//
//        private fun parseTriggers(jsonArray: JSONArray): Set<Trigger> {
//            val triggers = mutableSetOf<Trigger>()
//
//            for (i in 0 until jsonArray.length()) {
//                val triggerJson = jsonArray.getJSONObject(i)
//                val trigger = Trigger.fromJson(triggerJson)
//                triggers.add(trigger)
//            }
//
//            return triggers
//        }
//
//        private fun parsePaywalls(jsonArray: JSONArray): List<Paywall> {
//            val paywalls = mutableListOf<Paywall>()
//
//            for (i in 0 until jsonArray.length()) {
//                val paywallJson = jsonArray.getJSONObject(i)
//                val paywall = Paywall.fromJson(paywallJson)
//                paywalls.add(paywall)
//            }
//
//            return paywalls
//        }
//
////        private fun parsePostbackRequest(jsonObject: JSONObject): PostbackRequest {
////            return PostbackRequest(
////                // Add properties based on the PostbackRequest class structure and JSON format
////            )
////        }
//
//
//        private fun parseFeatureFlags(jsonArray: JSONArray): FeatureFlags {
//            return FeatureFlags.fromJson(jsonArray)
//        }
//
//        private fun parsePreloadingDisabled(jsonObject: JSONObject): PreloadingDisabled {
//            return PreloadingDisabled.fromJson(jsonObject)
//        }
//    }
//}
