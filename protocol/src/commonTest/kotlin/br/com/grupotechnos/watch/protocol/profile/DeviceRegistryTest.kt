package br.com.grupotechnos.watch.protocol.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Identificação pelo nome do scan, com os prefixos do Condor Reebok ([DeviceRegistry]). */
class DeviceRegistryTest {
	@Test
	fun condorReebokNamesResolveToTheCrpProfile() {
		for (name in listOf("P85", "P85 ", "P85-1234", "Condor Reebok", " Condor Reebok", "P85\u0000")) {
			assertEquals("condorreebok", DeviceRegistry.identify(name, emptyList())?.id, "name '$name'")
		}
		assertNull(DeviceRegistry.identify("P8", emptyList()))
		assertNull(DeviceRegistry.identify("XP85", emptyList()))
		assertNull(DeviceRegistry.identify("", emptyList()))
		assertNull(DeviceRegistry.identify(null, emptyList()))
		assertEquals(Family.CRP, DeviceRegistry.byId("condorreebok")?.family)
	}

	@Test
	fun smaAndIdoKeepTheirExactNames() {
		assertEquals("fitgps", DeviceRegistry.identify("Mormaii_fitGPS", emptyList())?.id)
		assertEquals("lifeultragps", DeviceRegistry.identify("Life Ultra GPS", emptyList())?.id)
		assertEquals("lifeultragps", DeviceRegistry.identify("Smart Watch 6010", emptyList())?.id)
		assertNull(DeviceRegistry.identify("Mormaii_fitGPS2", emptyList()))
		assertTrue(Profiles.MORMAII_FIT_GPS.bleNamePrefixes.isEmpty())
		assertTrue(Profiles.MORMAII_LIFE_ULTRA_GPS.bleNamePrefixes.isEmpty())
	}

	@Test
	fun matchesNameIsPublicAndTrims() {
		assertTrue(DeviceRegistry.matchesName(Profiles.CONDOR_REEBOK, "  P85xyz"))
		assertFalse(DeviceRegistry.matchesName(Profiles.CONDOR_REEBOK, "Mormaii_fitGPS"))
		assertFalse(DeviceRegistry.matchesName(Profiles.MORMAII_FIT_GPS, "P85"))
	}

	@Test
	fun condorReebokProfileValues() {
		val profile = Profiles.CONDOR_REEBOK
		assertEquals(listOf("P85", "Condor Reebok"), profile.bleNames)
		assertEquals("reebok", profile.slug)
		assertEquals("Condor Reebok", profile.appName)
		assertEquals(StepsAggregation.HOURLY_SUM, profile.quirks.stepsAggregation)
		assertEquals(200L, profile.quirks.commandGapMs)
		assertTrue(profile.quirks.classicBond)
		assertTrue(profile.quirks.iosTransportBridging)
		assertEquals(
			listOf("00002a37-0000-1000-8000-00805f9b34fb", "0000fee1-0000-1000-8000-00805f9b34fb", "00002a19-0000-1000-8000-00805f9b34fb"),
			profile.gattCandidates.single().sideNotify.map { it.characteristic },
		)
		assertEquals(0L, Profiles.MORMAII_FIT_GPS.quirks.commandGapMs)
		assertFalse(Profiles.MORMAII_LIFE_ULTRA_GPS.quirks.classicBond)
	}
}
