package com.cellularproxy.proxy.server

import com.cellularproxy.proxy.protocol.ParsedProxyRequest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ProxyTrafficActivityTracker {
    private val activeProxyExchangeCount = AtomicLong(0)

    val activeProxyExchanges: Long
        get() = activeProxyExchangeCount.get()

    fun begin(request: ParsedProxyRequest): ProxyTrafficActivityReservation =
        when (request) {
            is ParsedProxyRequest.HttpProxy,
            is ParsedProxyRequest.ConnectTunnel,
            -> beginProxyTraffic()
            is ParsedProxyRequest.Management -> ProxyTrafficActivityReservation.Noop
        }

    private fun beginProxyTraffic(): ProxyTrafficActivityReservation {
        activeProxyExchangeCount.incrementAndGet()
        return ProxyTrafficActivityReservation.Counted {
            activeProxyExchangeCount.decrementAndGet()
        }
    }
}

sealed interface ProxyTrafficActivityReservation {
    fun finish()

    data object Noop : ProxyTrafficActivityReservation {
        override fun finish() = Unit
    }

    class Counted internal constructor(
        private val finishAction: () -> Unit,
    ) : ProxyTrafficActivityReservation {
        private val finished = AtomicBoolean(false)

        override fun finish() {
            if (finished.compareAndSet(false, true)) {
                finishAction()
            }
        }
    }
}
