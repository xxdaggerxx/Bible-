package com.biblestudy.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.create
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
actual fun httpRequest(
    url: String,
    method: String,
    headers: Map<String, String>,
    body: ByteArray?,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
): HttpResult {
    val request = NSMutableURLRequest(NSURL(string = url))
    request.setHTTPMethod(method)
    request.setTimeoutInterval((connectTimeoutMs + readTimeoutMs) / 1000.0)
    for ((k, v) in headers) request.setValue(v, forHTTPHeaderField = k)
    if (body != null) request.setHTTPBody(body.toNSData())
    val done = dispatch_semaphore_create(0)
    var status = 0
    var data: NSData? = null
    var failure: String? = null
    NSURLSession.sharedSession.dataTaskWithRequest(request) { d, response, error ->
        data = d
        status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
        failure = error?.localizedDescription
        dispatch_semaphore_signal(done)
    }.resume()
    dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
    failure?.let { throw okio.IOException(it) }
    return HttpResult(status, data?.toByteArray() ?: ByteArray(0))
}

actual fun sleepMillis(ms: Long) = NSThread.sleepForTimeInterval(ms / 1000.0)

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned {
    NSData.create(bytes = it.addressOf(0), length = size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    val out = ByteArray(n)
    if (n > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
