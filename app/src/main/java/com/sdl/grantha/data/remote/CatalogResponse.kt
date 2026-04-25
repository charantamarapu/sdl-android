package com.sdl.grantha.data.remote

import com.google.gson.annotations.SerializedName

/**
 * Response model for the /api/mobile/catalog endpoint.
 */
data class CatalogResponse(
    @SerializedName("granthas") val granthas: List<GranthaItem>,
    @SerializedName("total") val total: Int
)

data class GranthaItem(
    @SerializedName("name") val name: String,
    @SerializedName("tags") val tags: String = "",
    @SerializedName("source_url") val sourceUrl: String = "",
    @SerializedName("identifier") val identifier: String = "",
    @SerializedName("size_bytes") val sizeBytes: Long = 0,
    @SerializedName("page_count") val pageCount: Int = 0,
    @SerializedName("checksum") val checksum: String = "",
    @SerializedName("books") val books: String = ""
)
