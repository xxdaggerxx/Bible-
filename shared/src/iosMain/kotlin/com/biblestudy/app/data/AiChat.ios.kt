package com.biblestudy.app.data

// TODO(iOS): the same request as AiChatSdk, as an HTTP call (the Java SDK can't run on iOS).
actual fun claudeChat(baseUrl: String?): ChatService = ChatService { _, _, _, _, _, _ ->
    ChatResult.Failed("The AI chat isn't available on iPad and iPhone yet.")
}
