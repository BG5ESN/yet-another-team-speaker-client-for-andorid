package dev.tsdroid.model

/** 服务器文件列表里的一项（来自 tsclientlib 的文件列表回调）。 */
data class TsFileEntry(
    val name: String,
    val size: Long,
    val datetime: Long,
    val isFile: Boolean,
)
