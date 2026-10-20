// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14
package org.openxplay.direlay.tls

import org.openxplay.direlay.SyntheticIdentity
import org.openxplay.direlay.storage.MaterialStatus
import java.io.File
import java.security.KeyStore
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TlsStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun validImportReloadReplaceAndRemove() {
        val root = temporary.newFolder()
        var changes = 0
        val store = TlsStore(root) { changes++ }
        val password = " secret with spaces ".toCharArray()
        assertNull(store.sslContext())
        val data = SyntheticIdentity().pkcs12(password)
        store.importFrom(data, password)
        assertEquals(MaterialStatus.Installed, store.status())
        assertNotNull(TlsStore(root).sslContext())
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(File(root, "tls").toPath()))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(File(root, "tls/password").toPath()))
        store.importFrom(SyntheticIdentity().pkcs12(charArrayOf()), charArrayOf())
        assertNotNull(store.sslContext())
        // The replaced key material is deleted; only the active generation remains.
        assertEquals(1, root.listFiles().orEmpty().count { it.name.startsWith(".tls-generation-") })
        store.remove()
        assertNull(store.sslContext())
        assertEquals(MaterialStatus.Missing, store.status())
        assertEquals(3, changes)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun wrongPasswordCorruptEmptyAndOversizedFilesPreserveExistingTls() {
        val root = temporary.newFolder()
        var changes = 0
        val store = TlsStore(root) { changes++ }
        val password = "test password".toCharArray()
        val data = SyntheticIdentity().pkcs12(password)
        store.importFrom(data, password)
        val original = File(root, "tls").canonicalPath
        val empty = java.io.ByteArrayOutputStream().also {
            KeyStore.getInstance("PKCS12").apply { load(null, password); store(it, password) }
        }.toByteArray()
        for ((bytes, secret) in listOf(
            data to "wrong".toCharArray(), byteArrayOf(1, 2, 3) to password,
            empty to password, ByteArray(TlsStore.MAX_FILE_BYTES + 1) to password,
        )) {
            assertThrows(Exception::class.java) { store.importFrom(bytes, secret) }
            assertEquals(original, File(root, "tls").canonicalPath)
            assertNotNull(store.sslContext())
        }
        assertEquals(1, changes)
        assertEquals(2, root.listFiles()!!.size)
        File(root, "tls/certificate.p12").writeBytes(byteArrayOf(1))
        assertTrue(store.status() is MaterialStatus.Invalid)
        assertThrows(Exception::class.java) { store.sslContext() }
    }

    @Test fun disappearingAndFlappingAddressesKeepCertificateButNewAddressesRegenerate() {
        val root = temporary.newFolder()
        val store = TlsStore(root)
        val first = setOf("192.168.1.2", "192.168.2.2")
        assertTrue(store.ensureDefault(first))
        val fingerprint = store.fingerprint()
        assertFalse(store.ensureDefault(setOf("192.168.1.2")))
        assertFalse(store.ensureDefault(first))
        assertEquals(fingerprint, store.fingerprint())
        assertTrue(store.ensureDefault(setOf("192.168.3.2")))
        assertNotEquals(fingerprint, store.fingerprint())
        assertFalse(store.ensureDefault(first))
        val lines = File(root, "tls-generated/addresses").readLines()
        assertEquals(listOf("192.168.3.2", "192.168.1.2", "192.168.2.2"), lines.drop(1))
        assertEquals(lines.drop(1).toSet(), generatedAddresses(root))
    }

    @Test fun generatedCoverageRetainsMostRecentAddressesUpToSixteen() {
        val root = temporary.newFolder()
        val store = TlsStore(root)
        val initial = (1..16).map { "192.168.1.$it" }.toSet()
        assertTrue(store.ensureDefault(initial))
        val previous = File(root, "tls-generated/addresses").readLines().drop(1)
        assertTrue(store.ensureDefault(setOf("192.168.2.1")))
        val covered = File(root, "tls-generated/addresses").readLines().drop(1)
        assertEquals(listOf("192.168.2.1") + previous.take(15), covered)
        assertEquals(16, generatedAddresses(root).size)
        assertEquals(covered.toSet(), generatedAddresses(root))
        assertFalse(store.ensureDefault(setOf(previous.first())))
        assertTrue(store.ensureDefault(setOf(previous.last())))
    }

    @Test fun newHostNameRegeneratesButOrderingOrADisappearingNameDoesNot() {
        val store = TlsStore(temporary.newFolder())
        assertTrue(store.ensureDefault(setOf("192.168.1.2"), linkedSetOf("a.local", "b.local")))
        assertFalse(store.ensureDefault(emptySet(), linkedSetOf("b.local", "a.local")))
        assertFalse(store.ensureDefault(emptySet(), setOf("b.local")))
        assertFalse(store.ensureDefault(setOf("192.168.1.2"), emptySet()))
        assertTrue(store.ensureDefault(setOf("192.168.1.2"), setOf("c.local")))
        assertFalse(store.ensureDefault(emptySet(), setOf("a.local")))
    }

    @Test fun legacyMetadataIsReadWithoutRegenerationUnlessCoverageIsMissing() {
        val root = temporary.newFolder()
        val store = TlsStore(root)
        assertTrue(store.ensureDefault(setOf("192.168.1.2", "192.168.2.2"), setOf("relay.local")))
        File(root, "tls-generated/addresses").writeText(
            "dns:openxplay-android.local,relay.local\n192.168.1.2\n192.168.2.2")
        val fingerprint = store.fingerprint()
        assertFalse(TlsStore(root).ensureDefault(setOf("192.168.1.2"), setOf("relay.local")))
        assertEquals(fingerprint, store.fingerprint())
        assertTrue(store.ensureDefault(setOf("192.168.3.2"), setOf("relay.local")))
    }

    @Test fun invalidGeneratedMaterialIsReplacedAndImportedMaterialAlwaysWins() {
        val root = temporary.newFolder()
        val store = TlsStore(root)
        assertTrue(store.ensureDefault(setOf("192.168.1.2")))
        File(root, "tls-generated/certificate.p12").writeBytes(byteArrayOf(1))
        assertTrue(store.ensureDefault(setOf("192.168.1.2")))
        store.importFrom(SyntheticIdentity().pkcs12(charArrayOf()), charArrayOf())
        val fingerprint = store.fingerprint()
        assertFalse(store.ensureDefault(setOf("192.168.3.2"), setOf("new.local")))
        assertEquals(fingerprint, store.fingerprint())
        File(root, "tls/certificate.p12").writeBytes(byteArrayOf(1))
        assertFalse(store.ensureDefault(setOf("192.168.4.2")))
        assertTrue(store.status() is MaterialStatus.Invalid)
    }

    private fun generatedAddresses(root: File): Set<String> {
        val keys = KeyStore.getInstance("PKCS12")
        val password = File(root, "tls-generated/password").readText().toCharArray()
        File(root, "tls-generated/certificate.p12").inputStream().use { keys.load(it, password) }
        val certificate = keys.getCertificate("relay") as java.security.cert.X509Certificate
        return certificate.subjectAlternativeNames.filter { it[0] == 7 }.map { it[1] as String }.toSet()
    }

    @Test fun webPortsFollowTlsSelection() {
        assertEquals(8080, TlsStore.port(false))
        assertEquals(8443, TlsStore.port(true))
    }
}
