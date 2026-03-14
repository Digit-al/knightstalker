// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.remote.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.util.TimeZone

class MacCookieInterceptor(
    private val macProvider: () -> String
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val mac = macProvider()
        val tz = TimeZone.getDefault().id
        val request = chain.request().newBuilder()
            .header("Cookie", "mac=$mac; stb_lang=en; timezone=$tz")
            .header("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C)")
            .header("X-User-Agent", "Model: MAG250; Link: STB")
            .build()
        return chain.proceed(request)
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
