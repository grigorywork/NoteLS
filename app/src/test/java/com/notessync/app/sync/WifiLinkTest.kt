package com.notessync.app.sync

import android.app.Application
import android.net.*
import com.notessync.app.sync.auto.wifiLink
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class WifiLinkTest {
    @Test fun `only a private IPv4 Wi-Fi link qualifies and partial cellular VPN public or IPv6-only links do not`() {
        // Framework callback objects have hidden constructors/mutators; reflection is fixture-only.
        val integer = Int::class.javaPrimitiveType!!
        val network = ReflectionHelpers.callConstructor(Network::class.java, ClassParameter.from(integer, 17))
        fun capabilities(vararg transports: Int) = NetworkCapabilities().also { caps -> transports.forEach {
            ReflectionHelpers.callInstanceMethod<NetworkCapabilities>(caps, "addTransportType", ClassParameter.from(integer, it))
        } }
        val wifi = capabilities(NetworkCapabilities.TRANSPORT_WIFI)
        fun properties(address: String) = LinkProperties().also {
            val link = ReflectionHelpers.callConstructor(LinkAddress::class.java,
                ClassParameter.from(java.net.InetAddress::class.java, java.net.InetAddress.getByName(address.substringBefore('/'))),
                ClassParameter.from(integer, address.substringAfter('/').toInt()))
            ReflectionHelpers.callInstanceMethod<Boolean>(it, "addLinkAddress", ClassParameter.from(LinkAddress::class.java, link))
        }
        val local = properties("192.168.1.2/24")
        assertEquals("192.168.1.2", wifiLink(network, wifi, local)!!.address)
        assertNull(wifiLink(network, null, local)); assertNull(wifiLink(network, wifi, null))
        assertNull(wifiLink(network, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR), local))
        assertNull(wifiLink(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_VPN), local))
        assertNull(wifiLink(network, wifi, properties("8.8.8.8/24")))
        assertNull(wifiLink(network, wifi, properties("2001:db8::1/64")))
    }
}
