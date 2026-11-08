package com.eva.drive.data.network

import com.eva.drive.domain.provider.DriveClientFactory
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.http.HttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.gson.GsonFactory
import javax.inject.Inject

internal class GoogleDriveClientFactory @Inject constructor() : DriveClientFactory {
	override val transport: HttpTransport by lazy { AndroidHttp.newCompatibleTransport() }
	override val jsonFactory: JsonFactory by lazy { GsonFactory.getDefaultInstance() }
}
