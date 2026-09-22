package com.localvoicetv

import org.junit.Assert.*
import org.junit.Test
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class QWeatherTlsTest {
    @Test fun bundledRootIsTheOfficialIsrgRootX1() {
        val root = QWeatherTls.rootCertificate()
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(root.encoded).joinToString("") { "%02X".format(it) }
        assertEquals("96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6", fingerprint)
        assertTrue(root.basicConstraints >= 0)
        root.verify(root.publicKey)
    }

    @Test fun customRootPolicyCannotBeAppliedToOtherHostsOrCleartext() {
        for (url in listOf("https://evil.example", "https://test.qweatherapi.com.evil.example", "http://test.qweatherapi.com")) {
            assertThrows(IllegalArgumentException::class.java) { QWeatherTls.openConnection(URL(url)) }
        }
    }

    @Test fun bothTrustManagersMustRejectAnUntrustedChain() {
        var checks = 0
        fun rejecting() = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw CertificateException() }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) { checks++; throw CertificateException() }
        }
        val combined = QWeatherTls.combinedTrustManager(rejecting(), rejecting())
        assertThrows(CertificateException::class.java) { combined.checkServerTrusted(emptyArray(), "RSA") }
        assertEquals(2, checks)
    }
}
