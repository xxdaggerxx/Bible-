package com.biblestudy.app.data

/** On iOS there's no Anthropic SDK that runs, so the chat calls the Claude API over HTTP. */
actual fun claudeChat(baseUrl: String?): ChatService = AiChatHttp.service(baseUrl)
