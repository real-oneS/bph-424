package com.example.bt_def

import android.bluetooth.BluetoothDevice

data class ListItem(
    val device:BluetoothDevice,
    val isChecked: Boolean
)
