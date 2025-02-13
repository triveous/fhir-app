/*
 * Copyright 2021-2024 Ona Systems, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.smartregister.fhircore.engine.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.parser.IParser
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.smartregister.fhircore.engine.BuildConfig
import org.smartregister.fhircore.engine.OpenSrpApplication
import org.smartregister.fhircore.engine.R
import org.smartregister.fhircore.engine.configuration.app.ConfigService
import org.smartregister.fhircore.engine.data.remote.auth.KeycloakService
import org.smartregister.fhircore.engine.data.remote.auth.OAuthService
import org.smartregister.fhircore.engine.data.remote.fhir.resource.FhirConverterFactory
import org.smartregister.fhircore.engine.data.remote.fhir.resource.FhirResourceService
import org.smartregister.fhircore.engine.data.remote.shared.TokenAuthenticator
import org.smartregister.fhircore.engine.domain.networkUtils.ErrorCodes.FAILED_TO_COMPLETE_REQUEST_ERROR_CODE
import org.smartregister.fhircore.engine.domain.networkUtils.ErrorCodes.FAILED_TO_OVERWRITE_URL_ERROR_CODE
import org.smartregister.fhircore.engine.domain.networkUtils.ErrorCodes.NO_INTERNET_CONNECTION_ERROR_CODE
import org.smartregister.fhircore.engine.domain.networkUtils.ErrorCodes.UNKNOWN_ERROR_CODE
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.engine.util.TimeZoneTypeAdapter
import org.smartregister.fhircore.engine.util.extension.getCustomJsonParser
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import timber.log.Timber
import java.net.SocketException
import java.net.UnknownHostException
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
class NetworkModule {
  private var _isNonProxy = BuildConfig.IS_NON_PROXY_APK

  @Singleton
  @Provides
  fun baseUrlsHolders(secureSharedPreference: SecureSharedPreference): BaseUrlsHolder =
    BaseUrlsHolder(secureSharedPreference)

  @Module
  @InstallIn(SingletonComponent::class)
  object DispatcherModule {
    @Provides
    fun provideDispatchers(): CoroutineDispatchers = object : CoroutineDispatchers {
      override val main = Dispatchers.Main
      override val io = Dispatchers.IO
      override val default = Dispatchers.Default
    }
  }

  @Provides
  @NoAuthorizationOkHttpClientQualifier
  fun provideAuthOkHttpClient() =
    OkHttpClient.Builder()
      .addInterceptor(
        HttpLoggingInterceptor().apply {
          level =
            if (BuildConfig.DEBUG) {
              HttpLoggingInterceptor.Level.BODY
            } else HttpLoggingInterceptor.Level.BASIC
          redactHeader(AUTHORIZATION)
          redactHeader(COOKIE)
        },
      )
      .connectTimeout(TIMEOUT_DURATION, TimeUnit.SECONDS)
      .readTimeout(TIMEOUT_DURATION, TimeUnit.SECONDS)
      .protocols(listOf(Protocol.HTTP_1_1))
      .retryOnConnectionFailure(true)
      .callTimeout(TIMEOUT_DURATION, TimeUnit.SECONDS)
      .build()

  @Provides
  @WithAuthorizationOkHttpClientQualifier
  fun provideOkHttpClient(
    tokenAuthenticator: TokenAuthenticator,
    sharedPreferencesHelper: SharedPreferencesHelper,
    openSrpApplication: OpenSrpApplication?,
    baseUrlsHolder: BaseUrlsHolder,
    @ApplicationContext context: Context
  ): OkHttpClient {
    return OkHttpClient.Builder()
      .addInterceptor(createUrlInterceptor(openSrpApplication,context))
      .addInterceptor(createAuthInterceptor(tokenAuthenticator, sharedPreferencesHelper))
      .addInterceptor(createLoggingInterceptor())
      .addInterceptor(createConnectionCheckInterceptor(context))
      .connectTimeout(TIMEOUT_DURATION, TimeUnit.SECONDS)
      .readTimeout(TIMEOUT_DURATION, TimeUnit.SECONDS)
      .callTimeout(TIMEOUT_DURATION, TimeUnit.SECONDS)
      .protocols(listOf(Protocol.HTTP_1_1))
      .retryOnConnectionFailure(true) // Avoid silent retries sometimes before token is provided
      .build()
  }

  @Provides
  fun provideGson(): Gson =
    GsonBuilder()
      .setLenient()
      .registerTypeAdapter(TimeZone::class.java, TimeZoneTypeAdapter().nullSafe())
      .create()

  @Provides fun provideParser(): IParser = FhirContext.forR4Cached().getCustomJsonParser()

  @Provides
  @Singleton
  fun provideKotlinJson() = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    isLenient = true
    useAlternativeNames = true
  }

  @Provides
  @AuthenticationRetrofit
  fun provideAuthRetrofit(
    @NoAuthorizationOkHttpClientQualifier okHttpClient: OkHttpClient,
    configService: ConfigService,
    gson: Gson,
    baseUrlsHolder: BaseUrlsHolder
  ): Retrofit =
    Retrofit.Builder()
      .baseUrl(baseUrlsHolder.oauthServerBaseUrl.value?:"")
      .client(okHttpClient)
      .addConverterFactory(GsonConverterFactory.create(gson))
      .build()

  @OptIn(ExperimentalSerializationApi::class)
  @Provides
  @KeycloakRetrofit
  fun provideKeycloakRetrofit(
    @WithAuthorizationOkHttpClientQualifier okHttpClient: OkHttpClient,
    configService: ConfigService,
    json: Json,
    baseUrlsHolder: BaseUrlsHolder
  ): Retrofit =
    Retrofit.Builder()
      .baseUrl(baseUrlsHolder.oauthServerBaseUrl.value?:"")
      .client(okHttpClient)
      .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE))
      .build()

  @Provides
  @RegularRetrofit
  fun provideRegularRetrofit(
    @WithAuthorizationOkHttpClientQualifier okHttpClient: OkHttpClient,
    configService: ConfigService,
    gson: Gson,
    parser: IParser,
    baseUrlsHolder: BaseUrlsHolder
  ): Retrofit =
    Retrofit.Builder()
      .baseUrl(baseUrlsHolder.fhirServerBaseUrl.value?:"")
      .client(okHttpClient)
      .addConverterFactory(FhirConverterFactory(parser))
      .addConverterFactory(GsonConverterFactory.create(gson))
      .build()

  @Provides
  fun provideOauthService(
    @AuthenticationRetrofit retrofit: Retrofit,
  ): OAuthService = retrofit.create(OAuthService::class.java)

  @Provides
  fun provideKeycloakService(@KeycloakRetrofit retrofit: Retrofit): KeycloakService =
    retrofit.create(KeycloakService::class.java)

  @Provides
  fun provideFhirResourceService(@RegularRetrofit retrofit: Retrofit): FhirResourceService =
    retrofit.create(FhirResourceService::class.java)

  @Provides
  @Singleton
  fun provideFHIRBaseURL(@ApplicationContext context: Context): OpenSrpApplication? =
    if (context is OpenSrpApplication) context else null

  private fun createUrlInterceptor(openSrpApplication: OpenSrpApplication?, context: Context): Interceptor {
    return Interceptor { chain ->
      var attempt = 0
      var request = chain.request()
      var response: Response? = null
      val maxRetries = 2

      while (attempt <= maxRetries) {
        try {
          request = modifyUrlIfNeeded(request, openSrpApplication)
          response = chain.proceed(request)
          break
        } catch (e: UnknownHostException) {
          Timber.e("Hostname resolution failed on attempt $attempt: ${e.message}")
          if (attempt >= maxRetries) {
            Timber.e("Max retries reached for hostname resolution.")
            throw e
          }
          attempt++
        } catch (e: Exception) {
          Timber.e(e, "Failed to overwrite URL request successfully")
          return@Interceptor buildErrorResponse(chain, FAILED_TO_OVERWRITE_URL_ERROR_CODE, e.message ?: context.getString(R.string.failed_to_overwrite_url_request_successfully), e)
        }
      }
      response ?: buildErrorResponse(chain, UNKNOWN_ERROR_CODE, context.getString(R.string.unknown_error), null)
    }
  }

  private fun modifyUrlIfNeeded(request: Request, openSrpApplication: OpenSrpApplication?): Request {
    val requestPath = request.url.encodedPath.substring(1)
    val resourcePath = if (!_isNonProxy) requestPath.replace("fhir/", "") else requestPath

    openSrpApplication?.let {
      if (request.url.host == it.getFhirServerHost()?.host && CUSTOM_ENDPOINTS.contains(resourcePath)) {
        val newUrl = request.url.newBuilder().encodedPath("/$resourcePath").build()
        return request.newBuilder().url(newUrl).build()
      }
    }

    return request
  }

  private fun createAuthInterceptor(
    tokenAuthenticator: TokenAuthenticator,
    sharedPreferencesHelper: SharedPreferencesHelper
  ): Interceptor {
    return Interceptor { chain ->
      try {
        val accessToken = tokenAuthenticator.getAccessToken()
        val requestBuilder = chain.request().newBuilder()
        if (accessToken.isNotEmpty()) {
          requestBuilder.addHeader(AUTHORIZATION, "Bearer $accessToken")
          requestBuilder.header("Connection", "close")
          sharedPreferencesHelper.retrieveApplicationId()?.let {
            requestBuilder.addHeader(APPLICATION_ID, it)
          }
        }
        chain.proceed(requestBuilder.build())
      } catch (e: SocketException) {
        Timber.e(e, "SocketException occurred, possibly due to a slow network.")
        // Handle retry logic or notify the user
        buildErrorResponse(chain, FAILED_TO_COMPLETE_REQUEST_ERROR_CODE, "Network error, please try again.", e)
      } catch (e: Exception) {
        Timber.e(e, "Failed to complete request successfully")
        buildErrorResponse(chain, FAILED_TO_COMPLETE_REQUEST_ERROR_CODE, e.message ?: tokenAuthenticator.context.getString(R.string.failed_to_complete_request_successfully), e)
      }
    }
  }

  private fun createLoggingInterceptor(): Interceptor {
    return HttpLoggingInterceptor().apply {
      level = if (BuildConfig.DEBUG) {
        HttpLoggingInterceptor.Level.BODY
      } else {
        HttpLoggingInterceptor.Level.BASIC
      }
      redactHeader(AUTHORIZATION)
      redactHeader(COOKIE)
    }
  }

  private fun createConnectionCheckInterceptor(context: Context): Interceptor {
    return Interceptor { chain ->
      if (!isInternetAvailable(context)) {
        return@Interceptor buildErrorResponse(
          chain,
          NO_INTERNET_CONNECTION_ERROR_CODE,
          context.getString(R.string.no_internet_connection),
          Exception(context.getString(R.string.no_internet_connection))
        )
      }
      chain.proceed(chain.request())
    }
  }

  private fun isInternetAvailable(context: Context): Boolean {
    var result = false
    val connectivityManager =
      context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
    connectivityManager?.let {
      it.getNetworkCapabilities(connectivityManager.activeNetwork)?.apply {
        result = when {
          hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
          hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
          else -> false
        }
      }
    }
    return result
  }

  private fun buildErrorResponse(
    chain: Interceptor.Chain,
    code: Int,
    message: String,
    e: Exception?
  ): Response {
    return Response.Builder()
      .request(chain.request())
      .protocol(Protocol.HTTP_1_1)
      .code(code)
      .message(message)
      .body("{$e}".toResponseBody(null))
      .build()
  }

  companion object {
    const val TIMEOUT_DURATION = 600L
    const val AUTHORIZATION = "Authorization"
    const val APPLICATION_ID = "App-Id"
    const val COOKIE = "Cookie"
    val JSON_MEDIA_TYPE = "application/json".toMediaType()
    val CUSTOM_ENDPOINTS = listOf("PractitionerDetail", "LocationHierarchy")
  }
}
