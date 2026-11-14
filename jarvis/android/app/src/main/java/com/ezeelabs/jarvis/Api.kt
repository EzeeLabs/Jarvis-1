package com.ezeelabs.jarvis

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, override val message: String) : Exception(message)

/** Blocking HTTP client: always call from Dispatchers.IO. */
class Api(private val store: Store) {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()

    fun call(method: String, path: String, body: JSONObject? = null, auth: Boolean = true): JSONObject {
        val base = store.baseUrl
        if (base.isEmpty()) throw ApiException(0, "Server URL is not set")
        val builder = Request.Builder().url(base + path)
        if (auth && store.token.isNotEmpty()) builder.header("Authorization", "Bearer " + store.token)
        val rb = (body?.toString() ?: "{}").toRequestBody(json)
        builder.method(method, if (method == "GET" || method == "DELETE") null else rb)
        try {
            http.newCall(builder.build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ApiException(r.code, errorText(text, r.code))
                return if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        } catch (e: IOException) {
            throw ApiException(0, "Cannot reach the server: " + (e.message ?: "network error"))
        }
    }

    private fun errorText(text: String, code: Int): String = try {
        val o = JSONObject(text)
        o.optString("message").ifEmpty { o.optString("detail") }.ifEmpty { "HTTP $code" }
    } catch (e: Exception) { "HTTP $code" }

    fun get(path: String) = call("GET", path)
    fun post(path: String, body: JSONObject = JSONObject()) = call("POST", path, body)
    fun put(path: String, body: JSONObject) = call("PUT", path, body)
    fun patch(path: String, body: JSONObject) = call("PATCH", path, body)
    fun delete(path: String) = call("DELETE", path)

    fun login(password: String): String =
        call("POST", "/api/auth/login", JSONObject().put("password", password), auth = false).getString("token")
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
