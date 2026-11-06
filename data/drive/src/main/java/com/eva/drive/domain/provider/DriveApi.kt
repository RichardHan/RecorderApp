package com.eva.drive.domain.provider

import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.HttpTransport
import com.google.api.client.json.JsonFactory

interface DriveClientFactory {
	val transport: HttpTransport
	val jsonFactory: JsonFactory
	fun requestInitializer(accessToken: String): HttpRequestInitializer = HttpRequestInitializer { request ->
		request.headers.authorization = "Bearer $accessToken"
	}
}
