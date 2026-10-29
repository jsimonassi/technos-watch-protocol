package br.com.grupotechnos.watch.protocol.domain

/**
 * Erro interno de protocolo ou de sessão.
 *
 * Nunca atravessa a API pública: a sessão captura e converte em `onError(requestId, code, message)`.
 *
 * @property code um dos valores de [WatchErrorCodes].
 */
internal class WatchException(
	val code: String,
	override val message: String,
	cause: Throwable? = null,
) : Exception(message, cause)
