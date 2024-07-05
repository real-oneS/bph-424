package com.example.bt_def.bluetooth

import android.bluetooth.BluetoothAdapter

class BluetoothController(private val adapter: BluetoothAdapter) {
    private var connectThread:ConnectThread? = null
    fun connect(mac:String,listener: Listener){
        if(adapter.isEnabled && mac.isNotEmpty()){
            val device = adapter.getRemoteDevice(mac)
            connectThread = ConnectThread(device, listener)
            connectThread?.start()
        }
    }
    fun sendMessage(message: ByteArray){
        connectThread?.sendMessage(message.toString())
    }
    fun closeConnection(){
        connectThread?.closeConnection()
    }
    companion object{
        const val BLUETOOH_CONNECTED = "bluetooth_connected"
        const val BLUETOOH_NO_CONNECTED = "bluetooth_no_connected"
    }
    interface Listener{
        fun onReceive(message:String)
    }
}