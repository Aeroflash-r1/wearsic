package com.wearsic.app.data

import kotlinx.serialization.Serializable

/**
 * A saved server configuration (name + URL + API key). Switching profiles is
 * how a watch moves between a Home-LAN server and the permanent Cloudflare
 * front door without retyping a 24-character address and key on a 1.4" face.
 */
@Serializable
data class ServerProfile(
    val id: String,
    val name: String,
    val url: String,
    val apiKey: String = ""
)
