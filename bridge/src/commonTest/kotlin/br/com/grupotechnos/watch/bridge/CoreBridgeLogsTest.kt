package br.com.grupotechnos.watch.bridge

import br.com.grupotechnos.watch.protocol.log.FrameDirection
import br.com.grupotechnos.watch.protocol.log.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Métodos de log pelo [CoreBridge], contra o host Android antigo (`getLogs`, `exportLogs`, `clearLogs`, `setLogLevel`). */
class CoreBridgeLogsTest {
	@Test
	fun getLogsMapsTheHostEntriesNewestFirst() = runBridge { b ->
		b.host.logs = listOf(
			CoreLogEntry(1_793_272_444_786, LogLevel.DEBUG, "Frame/6e400003", "seq=2 03/02 BIND_RESPONSE len=14", FrameDirection.RX, "AB000006"),
			CoreLogEntry(1_793_272_444_700, LogLevel.INFO, "CoreBridge/onStateChanged", "state=READY phase=null", null, null),
			CoreLogEntry(1_793_272_444_600, LogLevel.WARN, "T/t", "older", null, null),
			CoreLogEntry(1, LogLevel.ERROR, "T/t", "oldest", null, null),
		)
		val completion = b.completion("getLogs")
		b.bridge.getLogs(3.9, completion)
		assertEquals(listOf("logEntries(3)"), b.host.queries)
		assertEquals(
			listOf(
				"resolve getLogs [" +
					"""{"ts":1793272444786.0,"level":"DEBUG","tag":"Frame/6e400003","message":"seq=2 03/02 BIND_RESPONSE len=14","dir":"RX","hex":"AB000006"},""" +
					"""{"ts":1793272444700.0,"level":"INFO","tag":"CoreBridge/onStateChanged","message":"state=READY phase=null","dir":null,"hex":null},""" +
					"""{"ts":1793272444600.0,"level":"WARN","tag":"T/t","message":"older","dir":null,"hex":null}]""",
			),
			b.journal.lines,
		)
		val first = (completion.value as List<*>).first() as Map<*, *>
		assertTrue(first["ts"] is Double)
		b.bridge.getLogs(-2.0, b.completion("getLogs"))
		assertEquals("resolve getLogs []", b.journal.lines.last())
		assertEquals("logEntries(-2)", b.host.queries.last(), "the host clamps")
	}

	@Test
	fun exportLogsResolvesWithThePathOrRejectsWithIoError() = runBridge { b ->
		b.bridge.exportLogs(b.completion("exportLogs"))
		b.host.export = { throw IllegalStateException("No space left on device") }
		b.bridge.exportLogs(b.completion("exportLogs"))
		b.host.export = { throw UnsupportedOperationException() }
		b.bridge.exportLogs(b.completion("exportLogs"))
		// A falha real não é RuntimeException: IOException do bufferedWriter no Android, NSError do Swift no iOS.
		b.host.export = { throw Exception("open failed: ENOSPC") }
		b.bridge.exportLogs(b.completion("exportLogs"))
		assertEquals(
			listOf(
				"resolve exportLogs \"/data/corenext/frames-20261028-143000.jsonl\"",
				"""event onError {"requestId":-1,"code":"IO_ERROR","message":"No space left on device","method":"exportLogs"}""",
				"reject exportLogs IO_ERROR No space left on device",
				"""event onError {"requestId":-1,"code":"IO_ERROR","message":"UnsupportedOperationException","method":"exportLogs"}""",
				"reject exportLogs IO_ERROR UnsupportedOperationException",
				"""event onError {"requestId":-1,"code":"IO_ERROR","message":"open failed: ENOSPC","method":"exportLogs"}""",
				"reject exportLogs IO_ERROR open failed: ENOSPC",
			),
			b.journal.lines,
		)
	}

	@Test
	fun clearLogsGoesToTheHost() = runBridge { b ->
		b.bridge.clearLogs()
		assertEquals(listOf("host clearLogs"), b.journal.lines)
	}

	@Test
	fun setLogLevelTruncatesTheNumberAndIgnoresInvalidLevels() = runBridge { b ->
		for (level in listOf(0.0, 1.0, 2.5, 3.9, Double.NaN, 4.0, -1.0, 7.0)) b.bridge.setLogLevel(level)
		assertEquals(
			listOf("host setLogLevel DEBUG", "host setLogLevel INFO", "host setLogLevel WARN", "host setLogLevel ERROR", "host setLogLevel DEBUG"),
			b.journal.lines,
			"NaN.toInt() is 0, as on the old host",
		)
		assertEquals(listOf("Invalid level 4.0", "Invalid level -1.0", "Invalid level 7.0"), b.logger.withTag("CoreBridge/setLogLevel"))
		assertTrue(b.logger.messages.filter { it.second == "CoreBridge/setLogLevel" }.all { it.first == LogLevel.WARN })
	}
}
