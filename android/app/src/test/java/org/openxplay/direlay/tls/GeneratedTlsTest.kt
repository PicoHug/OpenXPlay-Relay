// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14
package org.openxplay.direlay.tls

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openxplay.direlay.SyntheticIdentity
import org.openxplay.direlay.storage.MaterialStatus
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey

class GeneratedTlsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun certificate(root: File): X509Certificate {
        val password = File(root, "tls-generated/password").readText().toCharArray()
        try {
            val keys = KeyStore.getInstance("PKCS12")
            File(root, "tls-generated/certificate.p12").inputStream().use { keys.load(it, password) }
            return keys.getCertificate("relay") as X509Certificate
        } finally { password.fill('\u0000') }
    }
    @Test fun certificateIsSelfSignedP256WithEveryAddressAndLocalhostSan() {
        val root = temporary.newFolder()
        val store = TlsStore(root)
        assertTrue(store.ensureDefault(setOf("192.168.49.1", "10.0.0.2")))
        val cert = certificate(root)
        cert.verify(cert.publicKey)
        cert.checkValidity()
        assertEquals("CN=OpenXPlay Relay", cert.subjectX500Principal.name)
        assertEquals(cert.issuerX500Principal, cert.subjectX500Principal)
        assertEquals(256, (cert.publicKey as ECPublicKey).params.curve.field.fieldSize)
        assertEquals("SHA256withECDSA", cert.sigAlgName)
        assertEquals(setOf(2 to "localhost", 2 to "openxplay-android.local", 7 to "192.168.49.1", 7 to "10.0.0.2"),
            cert.subjectAlternativeNames.map { (it[0] as Int) to (it[1] as String) }.toSet())
        assertEquals(MaterialStatus.Installed, store.status())
        assertNotNull(store.sslContext())
        assertTrue(requireNotNull(store.fingerprint()).matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
    }
    @Test fun unchangedSetAndProcessReloadReuseCertificateAddedAddressRegeneratesRemovedOneDoesNot() {
        val root = temporary.newFolder()
        val store = TlsStore(root)
        store.ensureDefault(setOf("10.0.0.2", "192.168.49.1"))
        val first = store.fingerprint()
        assertFalse(TlsStore(root).ensureDefault(linkedSetOf("192.168.49.1", "10.0.0.2")))
        assertEquals(first, store.fingerprint())
        assertTrue(store.ensureDefault(setOf("10.0.0.3")))
        assertNotEquals(first, store.fingerprint())
        val second = store.fingerprint()
        assertFalse(store.ensureDefault(emptySet()))
        assertEquals(second, store.fingerprint())
        assertEquals(setOf("10.0.0.3", "10.0.0.2", "192.168.49.1"),
            certificate(root).subjectAlternativeNames.filter { it[0] == 7 }.map { it[1] }.toSet())
        assertEquals(1, root.listFiles().orEmpty().count { it.name.startsWith(".tls-generated-generation-") })
    }
    @Test fun importedCertificateWinsAndRemovingItRestoresGeneratedTls() {
        val root = temporary.newFolder()
        val store = TlsStore(root)
        store.ensureDefault(setOf("10.0.0.2"))
        val generated = store.fingerprint()
        store.importFrom(SyntheticIdentity().pkcs12(charArrayOf()), charArrayOf())
        val imported = store.fingerprint()
        assertTrue(store.hasImported())
        assertNotEquals(generated, imported)
        assertFalse(store.ensureDefault(setOf("10.0.0.3")))
        assertEquals(imported, TlsStore(root).fingerprint())
        File(root, "tls/certificate.p12").writeBytes(byteArrayOf(1))
        assertFalse(store.ensureDefault(setOf("10.0.0.4")))
        assertThrows(IllegalStateException::class.java) { store.sslContext() }
        store.remove()
        assertFalse(store.hasImported())
        assertTrue(store.ensureDefault(setOf("10.0.0.4")))
        assertNotNull(store.sslContext())
        assertNotEquals(imported, store.fingerprint())
    }
}
