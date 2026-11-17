package com.toolbox.ztefamily.adb

import android.content.Context
import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPrivateCrtKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * Generates (once) and reuses an RSA keypair for ADB authentication, exactly
 * like the desktop adb client keeps ~/.android/adbkey. The private key never
 * leaves the device; only the public key is ever sent over the wire, and
 * only after the phone's own ADB auth dialog has been accepted by the user.
 */
class AdbCrypto private constructor(private val keyPair: KeyPair) {

    /** Signs a 20-byte SHA-1-less ADB auth token using raw RSA (PKCS#1 v1.5), as adb requires. */
    fun signToken(token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.private)
        return cipher.doFinal(padForAdb(token))
    }

    /** ADB's public key wire format: "<base64 mincrypt RSAPublicKey> user@host". */
    fun encodedPublicKey(label: String = "toolbox@otg"): ByteArray {
        val encoded = AdbPublicKeyEncoder.encode(keyPair.public as java.security.interfaces.RSAPublicKey)
        val b64 = Base64.encodeToString(encoded, Base64.NO_WRAP)
        return "$b64 $label\u0000".toByteArray(Charsets.UTF_8)
    }

    private fun padForAdb(token: ByteArray): ByteArray {
        // adbd expects the signature over the raw 20-byte token already
        // formatted by the caller; no extra ASN.1 digest wrapper is added
        // because adb's AUTH flow signs the token bytes directly.
        return token
    }

    companion object {
        private const val KEY_FILE = "adb_keypair.pem"

        fun loadOrGenerate(context: Context): AdbCrypto {
            val file = File(context.filesDir, KEY_FILE)
            return if (file.exists()) {
                AdbCrypto(readKeyPair(file))
            } else {
                val kp = generate()
                writeKeyPair(file, kp)
                AdbCrypto(kp)
            }
        }

        private fun generate(): KeyPair {
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048)
            return gen.generateKeyPair()
        }

        private fun writeKeyPair(file: File, kp: KeyPair) {
            val priv = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
            val pub = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
            file.writeText("$priv\n$pub")
            file.setReadable(true, true)
            file.setWritable(true, true)
        }

        private fun readKeyPair(file: File): KeyPair {
            val lines = file.readLines()
            val kf = KeyFactory.getInstance("RSA")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(lines[0], Base64.NO_WRAP)))
            val pub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(lines[1], Base64.NO_WRAP)))
            return KeyPair(pub, priv)
        }
    }
}

/**
 * Encodes an RSA public key into Android's legacy "mincrypt" RSAPublicKey
 * struct, which is the binary format adbd expects in AUTH_RSAPUBLICKEY.
 * Layout (all little-endian uint32 unless noted): modulus_len_words, n0inv,
 * modulus[], rr[], exponent.
 */
private object AdbPublicKeyEncoder {
    private const val ANDROID_PUBKEY_MODULUS_SIZE = 2048 / 8

    fun encode(key: java.security.interfaces.RSAPublicKey): ByteArray {
        val modulus = key.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = modulus.negate().modInverse(r32)
        val r = BigInteger.ONE.shiftLeft(ANDROID_PUBKEY_MODULUS_SIZE * 8)
        val rr = r.multiply(r).mod(modulus)

        val out = java.io.ByteArrayOutputStream()
        fun writeU32(v: Long) {
            out.write((v and 0xFF).toInt())
            out.write(((v shr 8) and 0xFF).toInt())
            out.write(((v shr 16) and 0xFF).toInt())
            out.write(((v shr 24) and 0xFF).toInt())
        }
        fun writeArray(b: BigInteger) {
            val bytes = toFixedLeBytes(b, ANDROID_PUBKEY_MODULUS_SIZE)
            out.write(bytes)
        }

        writeU32((ANDROID_PUBKEY_MODULUS_SIZE / 4).toLong())
        writeU32(n0inv.toLong() and 0xFFFFFFFFL)
        writeArray(modulus)
        writeArray(rr)
        writeU32(key.publicExponent.toLong())
        return out.toByteArray()
    }

    private fun toFixedLeBytes(value: BigInteger, size: Int): ByteArray {
        val be = value.toByteArray().let { if (it[0].toInt() == 0 && it.size > size) it.copyOfRange(1, it.size) else it }
        val fixed = ByteArray(size)
        val copyLen = minOf(be.size, size)
        for (i in 0 until copyLen) fixed[i] = be[be.size - 1 - i]
        return fixed
    }
}
