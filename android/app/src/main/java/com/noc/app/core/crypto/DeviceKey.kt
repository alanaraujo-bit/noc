package com.noc.app.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Identidade deste celular: par ECDSA P-256 no Android Keystore.
 * A chave privada é não exportável (fica no TEE/hardware); nenhum token ou segredo é salvo em disco.
 */
class DeviceKey private constructor(private val privateKey: PrivateKey, override val publicSpki: ByteArray) : DeviceSigner {

    val deviceId: String = Wire.idFromSpki(publicSpki)

    override fun sign(data: ByteArray): ByteArray {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(privateKey)
        s.update(data)
        return s.sign() // DER
    }

    companion object {
        private const val ALIAS = "noc.device.identity.v1"

        fun loadOrCreate(): DeviceKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val entry = ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
            if (entry != null) return DeviceKey(entry.privateKey, entry.certificate.publicKey.encoded)

            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            kpg.initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            val kp = kpg.generateKeyPair()
            return DeviceKey(kp.private, kp.public.encoded)
        }

        /** Apaga a identidade (usado em "Desvincular tudo"): o PC passa a não reconhecer mais este celular. */
        fun reset() {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        }
    }
}
