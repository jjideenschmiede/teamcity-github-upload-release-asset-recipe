@file:Repository("https://download.jetbrains.com/teamcity-repository/")
@file:Repository("https://repo1.maven.org/maven2/")
@file:DependsOn("org.jetbrains.teamcity:serviceMessages:2024.12")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
@file:CompilerOptions("-opt-in=kotlin.RequiresOptIn")

import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

val githubAPIVersion: String = "2022-11-28"

val token: String = System.getenv("input_github_access_token") ?: error("Input \"github_access_token\" is not set.")
val repository: String = System.getenv("input_repository") ?: error("Input \"repository\" is not set.")
var releaseId: String = System.getenv("input_release_id") ?: error("Input \"release_id\" is not set.")
val path: String = System.getenv("input_asset_path") ?: error("Input \"asset_path\" is not set.")
val name: String = System.getenv("input_asset_name") ?: ""

val file: File = File(path)
if (!file.exists()) error("Asset file not found: ${file.absolutePath}")
if (!file.isFile) error("Asset path is not a file: ${file.absolutePath}")

if (releaseId.isBlank()) {
	val client: HttpClient = HttpClient.newBuilder().build()
	val request: HttpRequest = HttpRequest.newBuilder()
		.uri(URI.create("https://api.github.com/repos/${repository}/releases/latest"))
		.header("Accept", "application/vnd.github+json")
		.header("Authorization", "Bearer $token")
		.header("X-GitHub-Api-Version", githubAPIVersion)
		.GET()
		.build()

	val response: HttpResponse<String> = client.send(request, HttpResponse.BodyHandlers.ofString())
	val statusCode: Int = response.statusCode()
	val responseBody: String = response.body()

	when (statusCode) {
		200 -> {
			val element = Json.parseToJsonElement(responseBody)
			releaseId = element.jsonObject["id"]?.jsonPrimitive?.content
			    ?: error("Could not extract release ID from response")

			println("Latest release found: $releaseId")
		}
		else -> error("Failed to fetch latest release (HTTP $statusCode): $responseBody")
	}
}

val finalAssetName: String = name.ifBlank { file.name }
val encodedAssetName: String = URLEncoder.encode(finalAssetName, "UTF-8")

println("Uploading $finalAssetName (${file.length()} bytes) to GitHub Release $releaseId...")

val client: HttpClient = HttpClient.newBuilder().build()
val request: HttpRequest = HttpRequest.newBuilder()
	.uri(URI.create("https://uploads.github.com/repos/${repository}/releases/${releaseId}/assets?name=${encodedAssetName}"))
	.header("Accept", "application/vnd.github+json")
	.header("Authorization", "Bearer $token")
	.header("X-GitHub-Api-Version", githubAPIVersion)
	.header("Content-Type", "application/octet-stream")
	.POST(HttpRequest.BodyPublishers.ofByteArray(file.readBytes()))
	.build()

val response: HttpResponse<String> = client.send(request, HttpResponse.BodyHandlers.ofString())
val statusCode: Int = response.statusCode()
val responseBody: String = response.body()

when (statusCode) {
	201 -> println("Successfully uploaded asset \"$finalAssetName\" to GitHub release $releaseId")
	else -> error("Upload failed (HTTP $statusCode): $responseBody")
}
