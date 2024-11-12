package com.credman.cmwallet.cbor

import java.lang.IllegalArgumentException

data class CborTag(
    val tag: Long,
    val item: Any?
)

fun cborDecode(data: ByteArray) : Any? {
    return Cbor().decode(data)
}

fun cborEncode(data: Any?) : ByteArray {
    return Cbor().encode(data)
}

class Cbor {
    data class Item (val item: Any?, val len: Int, val type: Int)
    data class Arg (val arg: Long, val len: Int)

    val TYPE_UNSIGNED_INT = 0x00
    val TYPE_NEGATIVE_INT = 0x01
    val TYPE_BYTE_STRING = 0x02
    val TYPE_TEXT_STRING = 0x03
    val TYPE_ARRAY = 0x04
    val TYPE_MAP = 0x05
    val TYPE_TAG = 0x06
    val TYPE_FLOAT = 0x07

    fun decode(data: ByteArray) : Any? {
        val ret = parseItem(data, 0)
        return ret.item
    }

    fun encode(data: Any?) : ByteArray {
        if (data == null) {
            return createArg(TYPE_FLOAT, 22)
        }
        if (data is Number) {
            if (data is Double) {
                throw IllegalArgumentException("Don't support doubles yet")
            } else {
                val value = data.toLong()
                if (value >= 0) {
                    return createArg(TYPE_UNSIGNED_INT, value)
                } else {
                    return createArg(TYPE_NEGATIVE_INT, -1 - value)
                }
            }
        }
        if (data is ByteArray) {
            return createArg(TYPE_BYTE_STRING, data.size.toLong()) + data
        }
        if (data is String) {
            return createArg(TYPE_TEXT_STRING, data.length.toLong()) + data.encodeToByteArray()
        }
        if (data is List<*>) {
            var ret = createArg(TYPE_ARRAY, data.size.toLong())
            for (i in data) {
                ret += encode(i)
            }
            return ret
        }
        if (data is Map<*,*>) {
            // TODO: maps must be sorted.
            // See: https://fidoalliance.org/specs/fido-v2.1-ps-20210615/fido-client-to-authenticator-protocol-v2.1-ps-20210615.html#ctap2-canonical-cbor-encoding-form
            var ret = createArg(TYPE_MAP, data.size.toLong())
            for (i in data) {
                ret += encode(i.key!!)
                ret += encode(i.value!!)
            }
            return ret
        }
        if (data is CborTag) {
            var ret = createArg(TYPE_TAG, data.tag)
            ret += encode(data.item)
            return ret
        }
        throw IllegalArgumentException("Bad type")
    }

    private fun getType(data: ByteArray, offset: Int) : Int {
        val d = data[offset].toInt()
        return (d and 0xFF) shr 5
    }

    private fun getArg(data: ByteArray, offset: Int) : Arg {
        val arg = data[offset].toLong() and 0x1F
        if (arg < 24) {
            return Arg(arg, 1)
        }
        if (arg == 24L) {
            return Arg(data[offset+1].toLong() and 0xFF, 2)
        }
        if (arg == 25L) {
            var ret = (data[offset+1].toLong() and 0xFF) shl 8
            ret = ret or (data[offset+2].toLong() and 0xFF)
            return Arg(ret, 3)
        }
        if (arg == 26L) {
            var ret = (data[offset+1].toLong() and 0xFF) shl 24
            ret = ret or ((data[offset+2].toLong() and 0xFF) shl 16)
            ret = ret or ((data[offset+3].toLong() and 0xFF) shl 8)
            ret = ret or (data[offset+4].toLong() and 0xFF)
            return Arg(ret, 5)
        }
        throw IllegalArgumentException("Bad arg")
    }

    private fun parseItem(data: ByteArray, offset: Int) : Item {
        val itemType = getType(data, offset)
        val arg = getArg(data, offset)
        //println("Type $itemType ${arg.arg} ${arg.len}")

        when (itemType) {
            TYPE_UNSIGNED_INT -> {
                return Item(arg.arg, arg.len, TYPE_UNSIGNED_INT)
            }
            TYPE_NEGATIVE_INT -> {
                return Item(-1 - arg.arg, arg.len, TYPE_NEGATIVE_INT)
            }
            TYPE_BYTE_STRING -> {
                val ret = data.sliceArray(offset+arg.len.toInt() until offset+arg.len.toInt()+arg.arg.toInt())
                return Item(ret, arg.len+arg.arg.toInt(), TYPE_BYTE_STRING)
            }
            TYPE_TEXT_STRING -> {
                val ret = data.sliceArray(offset+arg.len.toInt() until offset+arg.len.toInt()+arg.arg.toInt())
                return Item(ret.toString(Charsets.UTF_8), arg.len+arg.arg.toInt(),
                    TYPE_TEXT_STRING)
            }
            TYPE_ARRAY -> {
                val ret = mutableListOf<Any?>()
                var consumed = arg.len
                for (i in 0 until arg.arg.toInt()) {
                    val item = parseItem(data, offset+consumed)
                    ret.add(item.item)
                    consumed += item.len
                }
                return Item(ret.toList(), consumed, TYPE_ARRAY)
            }
            TYPE_MAP -> {
                val ret = mutableMapOf<Any?, Any?>()
                var consumed = arg.len
                for (i in 0 until arg.arg.toInt()) {
                    val key = parseItem(data, offset+consumed)
                    consumed += key.len
                    val value = parseItem(data, offset+consumed)
                    consumed += value.len
                    ret[key.item] = value.item
                }
                return Item(ret.toMap(), consumed, TYPE_MAP)
            }
            TYPE_TAG -> {
                val tagItem = parseItem(data, offset+arg.len)
                return Item(CborTag(arg.arg, tagItem.item), arg.len+tagItem.len, TYPE_TAG)
            }
            TYPE_FLOAT -> {
                if (arg.arg.toInt() == 22) {
                    return Item(null, arg.len, TYPE_FLOAT)
                } else if(arg.arg.toInt() == 20) {
                    return Item(false, arg.len, TYPE_FLOAT)
                } else if(arg.arg.toInt() == 21) {
                    return Item(true, arg.len, TYPE_FLOAT)
                } else {
                    throw IllegalArgumentException("Bad float $arg")
                }
            }
            else -> {
                throw IllegalArgumentException("Bad type")
            }
        }
    }

    private fun createArg(type: Int, arg: Long) : ByteArray {
        val t = type shl 5
        val a = arg.toInt()
        if (arg < 24) {
            return byteArrayOf(((t or a) and 0xFF).toByte())
        }
        if (arg <= 0xFF) {
            return byteArrayOf(
                ((t or 24) and 0xFF).toByte(),
                (a and 0xFF).toByte()
            )
        }
        if (arg <= 0xFFFF) {
            return byteArrayOf(
                ((t or 25) and 0xFF).toByte(),
                ((a shr 8) and 0xFF).toByte(),
                (a and 0xFF).toByte()
            )
        }
        if (arg <= 0xFFFFFFFF) {
            return byteArrayOf(
                ((t or 26) and 0xFF).toByte(),
                ((a shr 24) and 0xFF).toByte(),
                ((a shr 16) and 0xFF).toByte(),
                ((a shr 8) and 0xFF).toByte(),
                (a and 0xFF).toByte()
            )
        }
        throw IllegalArgumentException("bad Arg")
    }
}