package br.com.grupotechnos.watch.bridge

import br.com.grupotechnos.watch.protocol.domain.ConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals

/** `ConnectionState` → `deviceConnectStates` do Core atual. Portado do host Android; roda nos quatro targets. */
class StateMapperTest {
	@Test
	fun everyStateHasTheExpectedCode() {
		val expected = mapOf(
			ConnectionState.DISCONNECTED to 0,
			ConnectionState.RECONNECTING to 0,
			ConnectionState.CONNECTING to 1,
			ConnectionState.CONFIGURING to 1,
			ConnectionState.HANDSHAKING to 1,
			ConnectionState.READY to 2,
			ConnectionState.SYNCING to 2,
			ConnectionState.BLUETOOTH_OFF to 4,
		)
		assertEquals(ConnectionState.entries.toSet(), expected.keys)
		for ((state, code) in expected) {
			assertEquals(code, StateMapper.toDeviceConnectState(state), "state=$state")
		}
	}

	@Test
	fun isConnectedOnlyWhenCommandsAreAccepted() {
		val connected = ConnectionState.entries.filter(StateMapper::isConnected).toSet()
		assertEquals(setOf(ConnectionState.READY, ConnectionState.SYNCING), connected)
	}
}
