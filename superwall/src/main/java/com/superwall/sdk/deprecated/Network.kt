//package com.superwall.sdk.api
//
//import android.os.Handler
//import android.os.Looper
//import com.superwall.sdk.Superwall
//import org.json.JSONObject
//import java.net.HttpURLConnection
//import java.net.URL
//import java.util.concurrent.Executors
//
//class Network {
//    companion object {
////        public endpoint = "https://api.superwall.me/v1"
//        val endpoint = "https://api.superwall.me/api/v1"
//
//        fun getStaticConfig(callback: (config: Config?) -> Unit) {
//            val executor = Executors.newSingleThreadExecutor()
//            val handler = Handler(Looper.getMainLooper())
//            executor.execute{
//                // Fetch the static configuration from the server
//                val url = URL(endpoint + "/static_config" + "?pk=" + Superwall.instance.apiKey)
//                val connection = url.openConnection() as HttpURLConnection
//                connection.requestMethod = "GET"
//
//                val responseCode = connection.responseCode
//                if (responseCode == HttpURLConnection.HTTP_OK) {
//                    val input = connection.inputStream.bufferedReader().use { it.readText() }
//
//                    // Parse string to JSONObject
//                    val json = JSONObject(input)
//
//                    // Parse the JSON response
//                    var config = Config.fromJson(json)
//
//                    println(config)
//                    handler.post{
//                        callback(config)
//                    }
//                } else {
//                    println("Error: $responseCode")
//                }
//
//                connection.disconnect()
//                handler.post{
//                    callback(null)
//                }
//            }
//        }
//    }
//}