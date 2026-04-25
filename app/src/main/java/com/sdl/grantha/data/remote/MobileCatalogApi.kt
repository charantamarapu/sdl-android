package com.sdl.grantha.data.remote

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Streaming

/**
 * Retrofit API interface for the mobile endpoints on the Flask server.
 */
interface MobileCatalogApi {

    /**
     * Fetch the catalog of all available granthas with metadata.
     */
    @GET("/api/mobile/catalog")
    suspend fun getCatalog(): CatalogResponse

    /**
     * Download a grantha as an encrypted .sdl binary file.
     * Uses @Streaming to avoid loading the entire file into memory.
     */
    @Streaming
    @GET("/api/mobile/download/{name}")
    suspend fun downloadGrantha(@Path("name", encoded = true) name: String): Response<ResponseBody>
}
