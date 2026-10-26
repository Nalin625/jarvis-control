package com.jarvis.control

/** Where an on-phone AI model plugs in. Takes the JPEG bytes and returns the enhanced JPEG bytes. */
interface Enhancer {
    fun enhance(jpeg: ByteArray): ByteArray
}

/** Used until a model is added: the photo is saved exactly as taken. */
object NoEnhancer : Enhancer {
    override fun enhance(jpeg: ByteArray): ByteArray = jpeg
}
