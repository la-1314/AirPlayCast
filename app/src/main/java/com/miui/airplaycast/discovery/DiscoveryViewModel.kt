package com.miui.airplaycast.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DiscoveryViewModel(app: Application) : AndroidViewModel(app) {

    private val discovery = AirPlayDiscovery(app)

    val devices: StateFlow<List<AirPlayDevice>> = discovery.devices
    val isDiscovering: StateFlow<Boolean> = discovery.isDiscovering

    private val _selectedDevice = MutableStateFlow<AirPlayDevice?>(null)
    val selectedDevice: StateFlow<AirPlayDevice?> = _selectedDevice.asStateFlow()

    init {
        startDiscovery()
    }

    fun startDiscovery() {
        viewModelScope.launch { discovery.startDiscovery() }
    }

    fun stopDiscovery() {
        viewModelScope.launch { discovery.stopDiscovery() }
    }

    fun selectDevice(device: AirPlayDevice?) {
        _selectedDevice.value = device
    }

    override fun onCleared() {
        super.onCleared()
        discovery.stopDiscovery()
    }
}
