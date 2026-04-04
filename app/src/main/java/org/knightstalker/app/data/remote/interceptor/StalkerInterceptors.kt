// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.remote.interceptor

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.net.URLEncoder
import java.util.TimeZone

class MacCookieInterceptor(
    private val macProvider: () -> String,
    private val baseUrlProvider: () -> String
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val rawMac = macProvider()
        val mac = URLEncoder.encode(rawMac, "UTF-8")
        val tz = TimeZone.getDefault().id
        val baseUrl = baseUrlProvider().trimEnd('/')
        val referer = if (baseUrl.isNotEmpty()) "$baseUrl/" else ""
        Log.d("StalkerInterceptor", "MAC raw=$rawMac encoded=$mac referer=$referer")
        val builder = chain.request().newBuilder()
            .header("Cookie", "mac=$mac; stb_lang=en; timezone=$tz")
            .header("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C)")
            .header("X-User-Agent", "Model: MAG250; Link: STB")
        if (referer.isNotEmpty()) {
            builder.header("Referer", referer)
        }
        return chain.proceed(builder.build())
    }
}

class StalkerAuthInterceptor(
    private val tokenProvider: () -> String?
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider()
        val builder = chain.request().newBuilder()
        if (!token.isNullOrEmpty()) {
            builder.header("Authorization", "Bearer $token")
        }
        return chain.proceed(builder.build())
    }
}
