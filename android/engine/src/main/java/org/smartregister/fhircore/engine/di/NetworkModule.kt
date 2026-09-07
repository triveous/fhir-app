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
import org.smartregister.fhircore.engine.domain.networkUtils.ConnectivityState
import org.smartregister.fhircore.engine.domain.networkUtils.NetworkConnectivity
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

  @Provides
  @NoAuthorizationOkHttpClientQualifier
  fun provideAuthOkHttpClient(@ApplicationContext context: Context) =
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
      .addInterceptor(createConnectionCheckInterceptor(context))
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
      .baseUrl(baseUrlsHolder.oauthServerBaseUrl.value.orPlaceholder())
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
      .baseUrl(baseUrlsHolder.oauthServerBaseUrl.value.orPlaceholder())
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
      .baseUrl(baseUrlsHolder.fhirServerBaseUrl.value.orPlaceholder())
      .client(okHttpClient)
      .addConverterFactory(FhirConverterFactory(parser))
      .addConverterFactory(GsonConverterFactory.create(gson))
      .build()

  // Retrofit requires a non-empty baseUrl at build time, but on a fresh install no
  // site has been picked yet, so the holder values are blank. Substitute a syntactically
  // valid placeholder; the only call that runs before site selection is
  // OAuthService.fetchSites(@Url ...), which provides its own absolute URL.
  private fun String?.orPlaceholder(): String =
    if (this.isNullOrBlank()) PLACEHOLDER_BASE_URL else this

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
      try {
        chain.proceed(modifyUrlIfNeeded(chain.request(), openSrpApplication))
      } catch (e: UnknownHostException) {
        // Deliberately not retried here. Android caches the negative DNS answer, so an immediate
        // second and third attempt fail identically and only triple the reported failures. Retries
        // belong to WorkManager's exponential backoff, which waits long enough to matter. Caught
        // and rethrown so it does not fall into the generic branch below and get swallowed into an
        // error response.
        throw e
      } catch (e: Exception) {
        Timber.e(e, "Failed to overwrite URL request successfully")
        buildErrorResponse(chain, FAILED_TO_OVERWRITE_URL_ERROR_CODE, e.message ?: context.getString(R.string.failed_to_overwrite_url_request_successfully), e)
      }
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
      // Only a genuinely absent network short-circuits the request. ConnectivityState.UNVALIDATED
      // is let through on purpose: Android's probe also fails on networks that simply block it
      // (campus and corporate firewalls), and refusing to sync on those would be worse than the
      // occasional failed attempt.
      if (NetworkConnectivity.currentState(context) == ConnectivityState.OFFLINE) {
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
    const val PLACEHOLDER_BASE_URL = "http://placeholder.invalid/"
    val JSON_MEDIA_TYPE = "application/json".toMediaType()
    val CUSTOM_ENDPOINTS = listOf("PractitionerDetail", "LocationHierarchy")
  }
}
