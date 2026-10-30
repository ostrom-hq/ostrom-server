package dev.ostrom.server.kernel.config

/** A configuration value is missing or invalid; the server refuses to start rather than guess. */
class InvalidConfigException(message: String) : Exception(message)
