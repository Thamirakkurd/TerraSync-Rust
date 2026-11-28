package com.terrasync.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build

/**
 * Event-driven check for genuinely usable internet (NET_CAPABILITY_INTERNET + NET_CAPABILITY_VALIDATED)
 * so dead 4G+ links fall back immediately to offline PMTiles and offline routing without per-tile Binder IPC.
 */
object NetworkState {
    private val lock = Any()
    @Volatile private var callbackRegistered = false
    @Volatile private var cachedOnline = false
    @Volatile private var appContextRef: Context? = null

    private fun isRobolectric(): Boolean =
        "robolectric".equals(Build.FINGERPRINT, ignoreCase = true)

    private fun queryDirect(cm: ConnectivityManager): Boolean {
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun register(ctx: Context?) {
        val appCtx = ctx?.applicationContext ?: return
        appContextRef = appCtx
        if (isRobolectric() || callbackRegistered) return
        synchronized(lock) {
            if (callbackRegistered) return
            runCatching {
                val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cachedOnline = queryDirect(cm)
                cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        cachedOnline = runCatching { queryDirect(cm) }.getOrDefault(false)
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities
                    ) {
                        cachedOnline = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    }

                    override fun onLost(network: Network) {
                        cachedOnline = runCatching { queryDirect(cm) }.getOrDefault(false)
                    }

                    override fun onUnavailable() {
                        cachedOnline = false
                    }
                })
                callbackRegistered = true
            }
        }
    }

    fun hasValidatedInternet(ctx: Context?): Boolean {
        val resolved = ctx ?: appContextRef ?: return true
        if (isRobolectric()) {
            return runCatching {
                val cm = resolved.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                queryDirect(cm)
            }.getOrDefault(false)
        }
        if (!callbackRegistered) {
            register(resolved)
        }
        if (callbackRegistered) {
            return cachedOnline
        }
        return runCatching {
            val cm = resolved.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            queryDirect(cm)
        }.getOrDefault(false)
    }
}
