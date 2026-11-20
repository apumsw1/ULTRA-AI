package com.ultra.assistant.commands

/** Local command classification. Actual execution runs through ToolRegistry & TaskExecutor. */
object CommandRouter {
    fun category(command: String): String = when {
        CommandParser.isNext(command) -> "MEDIA_NEXT"
        command.contains("youtube", true) || command.contains("গান") || command.contains("song", true) -> "YOUTUBE_SEARCH"
        command.contains("open", true) || command.contains("খোলো") || command.contains("खोलो") -> "APP_OPEN"
        CommandParser.isSearch(command) -> "WEB_SEARCH"
        else -> "AGENT_REASONING"
    }
}
