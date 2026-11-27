package com.mobileaiagent.domain.model

data class ProjectInfo(
    val id: String,
    val name: String,
    val description: String? = null,
    val rootPath: String,
    val createdAt: Long,
    val updatedAt: Long,
    val fileCount: Int = 0,
    val sizeBytes: Long = 0,
    val hasActivePlan: Boolean = false,
)
