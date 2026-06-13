package com.example.polyglotapp
// This file is distributed under the open license AGPLv3, source code: https://github.com/cesslav/Polyglot_Mobile.
data class ModelInfo(
    val name: String,
    val file: String,
    val size_mb: Int,
    val input_language: String = "",
    val output_language: String = "",
    val bidirectional: Boolean = false,
)
