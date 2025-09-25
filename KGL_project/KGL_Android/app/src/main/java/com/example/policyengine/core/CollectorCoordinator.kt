package com.example.policyengine.core

object CollectorCoordinator {
    @Volatile private var owner: String? = null

    @Synchronized
    fun tryAcquire(newOwner: String): Boolean {
        if (owner == null) { owner = newOwner; return true }
        return owner == newOwner
    }

    @Synchronized
    fun release(curOwner: String) {
        if (owner == curOwner) owner = null
    }

    fun isRunning(): Boolean = owner != null
}
