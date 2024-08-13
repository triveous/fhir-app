@file:Suppress("BlockingMethodInNonBlockingContext")

package org.smartregister.fhircore.engine.domain.networkUtils

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import org.smartregister.fhircore.engine.domain.networkUtils.HttpConstants.SOMETHING_WANT_WRONG
import retrofit2.Response
import java.net.HttpURLConnection

/**
 * Created by Jeetesh Surana.
 */

abstract class SafeApiRequest(val context: Context) {

    suspend fun <T : Any> apiRequest(call: suspend () -> Response<T>): T {
        val response = call.invoke()
        if (response.isSuccessful) {
            return response.body()!!
        } else {
            val error = response.errorBody()?.string()
            val message = StringBuilder()

            if (!error.isNullOrBlank()) {
                try {
                    val errorJson = JSONObject(error)

                    // Extract the "error" message from the response
                    if (errorJson.has("error")) {
                        message.append(errorJson.getString("error"))
                    }

                    // Optionally, you can also extract and append other details like "status", "timestamp", and "path"
                    if (errorJson.has("status")) {
                        message.append(" (Status: ${errorJson.getInt("status")})")
                    }
                    if (errorJson.has("path")) {
                        message.append(" at ${errorJson.getString("path")}")
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                    message.append("An unexpected error occurred.")
                }
            } else {
                message.append("Unknown error occurred with status code ${response.code()}")
            }

            // Provide specific messages for common status codes
            when (response.code()) {
                404 -> message.append(" - The requested resource was not found.")
                500 -> message.append(" - Internal server error occurred.")
                HttpURLConnection.HTTP_CLIENT_TIMEOUT -> message.append(SOMETHING_WANT_WRONG)
            }

            throw ApiException(message.toString())
        }
    }
}
