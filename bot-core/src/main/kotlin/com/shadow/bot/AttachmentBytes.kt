package com.shadow.bot

import java.io.ByteArrayOutputStream
import java.io.InputStream

fun readAttachmentBytes(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size().toLong() + count <= 20L * 1024 * 1024) { "单个附件不能超过 20 MiB。" }
        output.write(buffer, 0, count)
    }
    require(output.size() > 0) { "不能发送空文件。" }
    return output.toByteArray()
}
