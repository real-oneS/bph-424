package com.example.bhp424

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.util.Log
import androidx.annotation.UiThread
import androidx.appcompat.app.AppCompatActivity
import com.example.bhp424.databinding.ActivityMainBinding
import com.example.bt_def.BluetoothConstants
import com.example.bt_def.bluetooth.BluetoothController
import com.google.android.material.snackbar.Snackbar


class MainActivity : AppCompatActivity(), BluetoothController.Listener{
    private lateinit var binding: ActivityMainBinding
    private lateinit var bluetoothController: BluetoothController
    private lateinit var btAdapter: BluetoothAdapter
    private var isRunning = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initBtAdapter()
        val pref = this.getSharedPreferences(BluetoothConstants.PREFERENCES, Context.MODE_PRIVATE)
        val mac = pref?.getString(BluetoothConstants.MAC,"")
        bluetoothController = BluetoothController(btAdapter)
        bluetoothController.connect(mac ?: "", this)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        bluetoothController.closeConnection()
    }
    private fun initBtAdapter(){
        val bManager = this.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter = bManager.adapter
    }

    override fun onReceive(message: String) {
        val pref = this.getSharedPreferences(BluetoothConstants.PREFERENCES, Context.MODE_PRIVATE)
        val mac = pref?.getString(BluetoothConstants.MAC,"")
        this.runOnUiThread{
            when(message){
                BluetoothController.BLUETOOH_CONNECTED->{
                    bphCollect(message)
                }
                BluetoothController.BLUETOOH_NO_CONNECTED->{
                    bluetoothController.connect(mac ?: "", this)
                }
                else ->{
                    binding.tvControl.text = message
                    bphCollect(message)

                }
            }
        }
    }

    private var currentMetering = true
    private var controlsReg = true
    private var slaveAddress: Byte = 0x0A
    private var data_table = UShortArray(256)
    private var data_current=UShortArray(256)
    fun modRTUCRC(buf: ByteArray, len: Int): UShort { // Расчёт CRC 16
        var crc = 0xFFFF // Основная переменная расчёта CRC
        for (pos in 0 until len) {
            crc = crc xor buf[pos].toInt() // Выполнение исключающего ИЛИ

            for (i in 8 downTo 1) {
                if ((crc and 0x0001)!= 0) { // Проверка последнего бита CRC
                    crc = (crc shr 1) //Сдвиг на 1 бит
                    crc = crc xor 0xA001 // Исключающее ИЛИ с полиномом
                } else {
                    crc = (crc shr 1)//Сдвиг на 1 бит
                }
            }
        }
        return crc.toUShort()
    }

    private fun CRC_check(mas: ByteArray, len: Int): Boolean {
        val crc = modRTUCRC(mas, len - 2)// Подсчёт CRC
        val crcHigh = (crc.toInt() shr 8).toByte() // Сдвигаем биты CRC на 8 позиций вправо и берем старший байт
        val crcLow = crc.toByte() // Берем младший байт CRC
        if(mas[len-1]==crcLow && mas[len-2] ==crcHigh){ // Сравниваем полученные байты с последними двумя байтами входного массива
            return true //Проверка посчитанного CRC с CRC принятого массива
        }
        else{
            return false
        }
    }
    private fun bphCollect(message: String) {
        var tryToWrite = 0 // Счетчик ошибочных запросов
        val maxTry = 2 // Максимальное количесво попыток опроса, при ошибочном CRC

        do {
            val readControlRegs = byteArrayOf(slaveAddress, 0x03, 0x00, 0x00, 0x00, 0x10) // Массив для отпроса регистров контроля БПХ
            val readCurrentMetering = byteArrayOf(slaveAddress, 0x03, 0x07,
                0xD0.toByte(), 0x00, 0x0E) // Массив для опроса текущих измерений БПХ
            val sendBuf = ByteArray(readControlRegs.size + 2) // Буфер для отправки байт
            val readBuf = ByteArray(256) // Буфер для чтения байт
            var countToRead = 0 // Переменная для посчета количества принятых байт
            if(controlsReg == true){ //Опрос регистров контроля
                readControlRegs.copyInto(sendBuf) // Копирование массива в буфер отправки
                val crc = modRTUCRC(readControlRegs, readControlRegs.size).toInt() // Подсчет CRC перед отправкой
                val crcHigh = (crc shr 8).toByte() // Сдвигаем биты CRC на 8 позиций вправо и берем старший байт
                val crcLow = crc.toByte() // Берем младший байт CRC
                sendBuf[sendBuf.size - 1] = crcHigh // Запись в буфер старшего байта
                sendBuf[sendBuf.size - 2] = crcLow//запись в буфер младшего байта
                bluetoothController.sendMessage(sendBuf) // Отправка байт
                Thread.sleep(500)
                try{ // Чтение
                    while(message==null){
                        Thread.sleep(100)
                    }
                    Log.d("MyLog", "message = " + message)
                    val byteMessage = message.toByteArray()
                    do {
                        readBuf[countToRead] = byteMessage[countToRead]
                        countToRead++
                    } while (countToRead < message.length)
                    Log.d("MyLog", "read buf = " + readBuf.toString())
                    Log.d("MyLog", "countread = " + countToRead.toString())
                    if (countToRead > 0 && CRC_check(readBuf, countToRead)) {
                        for (j in 0 until 16) {
                            data_table[j] = (((readBuf[2 * j + 3].toInt() shl 8) or readBuf[2 * j + 4].toInt()) as UShort)
                        }

                        val slaveAddr = data_table[0].toString()
                        val dutyCycle = data_table[1].toString()
                        val modemTimeout = data_table[2].toString()
                        val exposNorm = data_table[3].toString()
                        val thresNorm = data_table[4].toString()
                        val exposHigh = data_table[5].toString()
                        val thresHigh = data_table[6].toString()
                        val seconds = data_table[8].toString() // разобраться что контректно вывести
                        val countDown = data_table[9].toString()
                        val archPointer = data_table[11].toString()
                        val temperature = data_table[12].toString()
                        val voltBattery = data_table[13].toString()
                        val currSleep = data_table[14].toString()
                        val currActive = data_table[15].toString()
                        Log.d("MyLog", "data table = ${data_table[0]}")
                        binding.slaveAddr.text = Editable.Factory.getInstance().newEditable(slaveAddr)
                        binding.dutyCycle.text = Editable.Factory.getInstance().newEditable(dutyCycle)
                        binding.modemTimeout.text = Editable.Factory.getInstance().newEditable(modemTimeout)
                        binding.exposNorm.text = Editable.Factory.getInstance().newEditable(exposNorm)
                        binding.thresNorm.text = Editable.Factory.getInstance().newEditable(thresNorm)
                        binding.exposHigh.text = Editable.Factory.getInstance().newEditable(exposHigh)
                        binding.thresHigh.text = Editable.Factory.getInstance().newEditable(thresHigh)
                        binding.seconds.text = Editable.Factory.getInstance().newEditable(seconds)
                        binding.countDown.text = Editable.Factory.getInstance().newEditable(countDown)
                        binding.archPointer.text = Editable.Factory.getInstance().newEditable(archPointer)
                        binding.temperature.text = Editable.Factory.getInstance().newEditable(temperature)
                        binding.voltBattery.text = Editable.Factory.getInstance().newEditable(voltBattery)
                        binding.currSleep.text = Editable.Factory.getInstance().newEditable(currSleep)
                        binding.currActive.text = Editable.Factory.getInstance().newEditable(currActive)
                        binding.ctrlRegHigh.text.clear() // Очистка ячейки ctrlReg.High
                        binding.ctrlRegLow.text.clear() // Очистка ячейки ctrlReg.Low

                        val ctrlChar = data_table[10].toString(2).toCharArray() // Конвертация 2х регистров в массив символов
                        var ctrlCount = 16 // Переменная, определяющая количество на которое надо разбить число
                        var countBit = 0 // Переменная для смещения в массиве ctrlChar
                        do { // Вывод данных в таблицу в двоичном виде
                            var zero=""
                            if (ctrlCount > ctrlChar.size) { // Если в левой части двоичного числа есть нули, то прописываем их в начале
                                zero+="0"
                                binding.ctrlRegHigh.append(zero) // 0 в начале
                            } else {
                                zero += ctrlChar[countBit].toString()
                                binding.ctrlRegHigh.append(zero) // Вывод числа
                                countBit++
                            }
                            ctrlCount--
                            if (ctrlCount % 4 == 0) { // Разбиение двоичного числа на группы по 4 цифры
                                binding.ctrlRegHigh.append(" ")
                            }
                        } while (ctrlCount > 8)
                        do{
                            var zero = ""
                            zero+=ctrlChar[countBit].toString()
                            binding.ctrlRegLow.append(zero)
                            countBit++
                            ctrlCount--
                            if(ctrlCount%4==0){
                                binding.ctrlRegLow.append(" ")
                            }
                        }while (ctrlCount!=0)


                    }
//                else{
//                    tryToWrite++
//                    if (tryToWrite>maxTry){
//                        bluetoothController.closeConnection()
//                        return
//                    }
//                    continue
//                }
                } catch (e:Exception){
                    tryToWrite++
                    if (tryToWrite>maxTry){
                        bluetoothController.closeConnection()
                        return
                    }
                    continue
                }
            }

            Thread.sleep(100)
            if(currentMetering==true){ //Опрос текущих измерений
                countToRead = 0
                val sendCurrentBuf = ByteArray(readCurrentMetering.size + 2)
                readCurrentMetering.copyInto(sendCurrentBuf)
                val crc = modRTUCRC(readCurrentMetering, readCurrentMetering.size).toInt() // Подсчет CRC перед отправкой
                val crcHigh = (crc shr 8).toByte() // Сдвигаем биты CRC на 8 позиций вправо и берем старший байт
                val crcLow = crc.toByte() // Берем младший байт CRC
                sendCurrentBuf[sendCurrentBuf.size - 1] = crcHigh // Запись в буфер старшего байта
                sendCurrentBuf[sendCurrentBuf.size - 2] = crcLow//запись в буфер младшего байта
                bluetoothController.sendMessage(sendCurrentBuf) // Отправка байт
                Thread.sleep(500)
                try {
                    val byteMessage = message.toByteArray()
                    do {
                        readBuf[countToRead] = byteMessage[countToRead]
                        countToRead++
                    } while (countToRead < message.length)
                    if (countToRead > 0 && CRC_check(readBuf, countToRead)) {
                        for (j in 0 until 16) {
                            data_current[j] = (((readBuf[2 * j + 3].toInt() shl 8) or readBuf[2 * j + 4].toInt()) as UShort)
                        }
                        val expTime = data_current[0].toString()
                        val chanel1count=data_current[1].toString()
                        val chanel1count10=data_current[2].toString()
                        val chanel1saved=data_current[3].toString()
                        val chanel2count=data_current[4].toString()
                        val chanel2count10=data_current[5].toString()
                        val chanel2saved=data_current[6].toString()
                        val chanel3count=data_current[7].toString()
                        val chanel3count10=data_current[8].toString()
                        val chanel3saved=data_current[9].toString()
                        val chanel4count=data_current[10].toString()
                        val chanel4count10=data_current[11].toString()
                        val chanel4saved=data_current[12].toString()
                        binding.expTime.text= Editable.Factory.getInstance().newEditable(expTime)
                        binding.chanel1Count.text= Editable.Factory.getInstance().newEditable(chanel1count)
                        binding.chanel1Count10.text= Editable.Factory.getInstance().newEditable(chanel1count10)
                        binding.chanel1Saved.text= Editable.Factory.getInstance().newEditable(chanel1saved)
                        binding.chanel2Count.text= Editable.Factory.getInstance().newEditable(chanel2count)
                        binding.chanel2Count10.text= Editable.Factory.getInstance().newEditable(chanel2count10)
                        binding.chanel2Saved.text= Editable.Factory.getInstance().newEditable(chanel2saved)
                        binding.chanel3Count.text= Editable.Factory.getInstance().newEditable(chanel3count)
                        binding.chanel3Count10.text= Editable.Factory.getInstance().newEditable(chanel3count10)
                        binding.chanel3Saved.text= Editable.Factory.getInstance().newEditable(chanel3saved)
                        binding.chanel4Count.text= Editable.Factory.getInstance().newEditable(chanel4count)
                        binding.chanel4Count10.text= Editable.Factory.getInstance().newEditable(chanel4count10)
                        binding.chanel4Saved.text= Editable.Factory.getInstance().newEditable(chanel4saved)
                    }
//                else{
//                    tryToWrite++
//                    if (tryToWrite>maxTry){
//                        bluetoothController.closeConnection()
//                        return
//                    }
//                    continue
//                }
                }catch(e:SecurityException){
                    tryToWrite++
                    if (tryToWrite>maxTry){
                        bluetoothController.closeConnection()
                        return
                    }
                    continue
                }
            }
            Thread.sleep(100)
            tryToWrite=0 // сброс счётчика
            break// убрать после полной настройки
        } while (true or !isRunning)
    }
    fun editedParam(message: String){
        when(message){
            BluetoothController.BLUETOOH_CONNECTED->{
                val column = 1
                val row = 1
                val address_write = byteArrayOf(0x00, 0x00)
                val count_reg = byteArrayOf(0x00, 0x00)
                if (column == 1) {
                    when (row) {
                        0 -> {
                            address_write[0] = 0x00
                            count_reg[0] = 0x01
                            Snackbar.make(binding.root, "Был изменен адрес устройства.", Snackbar.LENGTH_LONG).show()
                        }
                        1 -> {
                            address_write[0] = 0x01
                            count_reg[0] = 0x01
                        }
                        2 -> {
                            address_write[0] = 0x02
                            count_reg[0] = 0x01
                        }
                        3 -> {
                            address_write[0] = 0x03
                            count_reg[0] = 0x01
                        }
                        4 -> {
                            address_write[0] = 0x04
                            count_reg[0] = 0x01
                        }
                        5 -> {
                            address_write[0] = 0x05
                            count_reg[0] = 0x01
                        }
                        6 -> {
                            address_write[0] = 0x06
                            count_reg[0] = 0x01
                        }
                        7 -> {
                            address_write[0] = 0x07
                            count_reg[0] = 0x02
                        }
                        else -> {
                            Snackbar.make(binding.root, "Ячейка не доступна для записи", Snackbar.LENGTH_LONG).show()
                            return
                        }
                    }
                }
                if (column == 3) {
                    when (row) {
                        0 -> {
                            address_write[0] = 0x09
                            count_reg[0] = 0x01
                        }
                        else -> {
                            Snackbar.make(binding.root, "Ячейка не доступна для записи", Snackbar.LENGTH_LONG).show()
                            return
                        }
                    }
                }
                val write_string = ByteArray(256)
                write_string[0] = 1
                write_string[1] = 0x10
                write_string[2] = address_write[1]
                write_string[3] = address_write[0]
                write_string[4] = count_reg[1]
                write_string[5] = count_reg[0]
                write_string[6] = (count_reg[0] + count_reg[0]).toByte()
                Thread.sleep(600)

                var crc = modRTUCRC(write_string, write_string.size).toInt()
                var crcHigh = (crc shr 8).toByte() // Сдвигаем биты CRC на 8 позиций вправо и берем старший байт
                var crcLow = crc.toByte() // Берем младший байт CRC
                write_string[write_string.count() - 2] = crcHigh
                write_string[write_string.count() - 1] = crcLow
                val request_buf = ByteArray(8) // Буфер для чтения байт с SerialPort
                val request_result = ByteArray(8)
//                Array.copy(write_string, request_result, 8)
                for (i in 0 until 3) {
                    bluetoothController.sendMessage(write_string)
                    Thread.sleep(600)
                }
                crc = modRTUCRC(write_string, write_string.size).toInt()
                crcHigh = (crc shr 8).toByte() // Сдвигаем биты CRC на 8 позиций вправо и берем старший байт
                crcLow = crc.toByte() // Берем младший байт CRC
                request_result[request_result.count() - 2] = crcHigh
                request_result[request_result.count() - 1] = crcLow
                if (request_buf[request_buf.count() - 2] != crcHigh && request_buf[request_buf.count() - 1] != crcLow) {
                    Snackbar.make(binding.root, "Ошибка записи", Snackbar.LENGTH_LONG).show()
                }
            }
        }

    }
}