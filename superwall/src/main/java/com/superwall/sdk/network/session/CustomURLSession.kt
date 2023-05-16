package com.superwall.sdk.network.session
//
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.withContext
//import java.io.BufferedReader
//import java.io.InputStreamReader
//import java.lang.Exception
//import java.lang.StringBuilder
//import java.net.HttpURLConnection
//import java.net.URL
//import java.util.Base64
//
//class CustomHttpUrlConnection {
//
//    enum class NetworkError(val errorDescription: String) {
//        Unknown("An unknown error occurred."),
//        NotAuthenticated("Unauthorized."),
//        Decoding("Decoding error."),
//        NotFound("Not found"),
//        InvalidUrl("URL invalid")
//    }
//
//    suspend fun request(endpoint: String): String {
//        var result = ""
//        withContext(Dispatchers.IO) {
//            try {
//                val url = URL(endpoint)
//                val connection = url.openConnection() as HttpURLConnection
//                connection.requestMethod = "GET"
//                connection.doInput = true
//                connection.doOutput = true
//
//                val auth = connection.getRequestProperty("Authorization")
//                    ?: throw NetworkError.NotAuthenticated
//
//                val startTime = System.currentTimeMillis()
//                val responseCode = connection.responseCode
//                if (responseCode == HttpURLConnection.HTTP_OK) {
//                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
//                    val response = StringBuilder()
//                    var responseLine: String?
//                    while (reader.readLine().also { responseLine = it } != null) {
//                        response.append(responseLine!!.trim { it <= ' ' })
//                    }
//                    result = response.toString()
//                } else {
//                    when (responseCode) {
//                        HttpURLConnection.HTTP_UNAUTHORIZED -> throw NetworkError.NotAuthenticated
//                        HttpURLConnection.HTTP_NOT_FOUND -> throw NetworkError.NotFound
//                        else -> throw NetworkError.Unknown
//                    }
//                }
//
//                val requestDuration = System.currentTimeMillis() - startTime
//                val requestId = connection.getHeaderField("x-request-id") ?: "unknown"
//
//                // Log here the request completed
//
//            } catch (e: Exception) {
//                // Log here the request error
//                throw NetworkError.Decoding
//            }
//        }
//        return result
//    }
//}
